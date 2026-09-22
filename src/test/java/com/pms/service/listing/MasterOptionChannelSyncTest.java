package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Structure sync (FEATURE_2608_06 / 86): an added master option reaches every channel switched OFF, a removed
 * one is switched off (never deleted), and propagation reconciles one cell — leaving an active leftover of a
 * market-registered cell alone.
 *
 * <p>Since FEATURE_2609_22/D1 every match here runs on {@code master_product_option_id}: a cell option keeps
 * its link however the channel renames it, and an option with no link at all is a <b>channel-only</b> option
 * (D2) that the master must never touch.</p>
 */
@ExtendWith(MockitoExtension.class)
class MasterOptionChannelSyncTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private ListingAssetService listingAssetService;
    @InjectMocks private MasterOptionChannelSyncImpl sync;

    private static final Long MASTER_ID = 10L;
    private static final MasterProduct MASTER = MasterProduct.builder().id(MASTER_ID).name("마스터").build();

    // ---------------------------------------------------------------- fixtures

    /** platformProductId null = DRAFT cell; non-null = market-registered. */
    private ProductListing cell(Long id, String platformProductId) {
        return ProductListing.builder().id(id).platform(Platform.COUPANG).name("셀-" + id)
                .masterProduct(MASTER).platformProductId(platformProductId).build();
    }

    private MasterProductOption option(Long id, String name) {
        return MasterProductOption.builder().id(id).name(name).masterProduct(MASTER).build();
    }

    /** 2609_22/D2: no link = channel-only option — the master owns nothing here. */
    private ProductListingOption cellOption(Long id, ProductListing cell, String name, boolean active) {
        return cellOption(id, cell, name, active, null);
    }

    /** 2609_22/D1: {@code linked} is the master option this cell option points at (the matching axis). */
    private ProductListingOption cellOption(Long id, ProductListing cell, String name, boolean active,
                                            MasterProductOption linked) {
        return ProductListingOption.builder().id(id).productListing(cell).optionName(name)
                .masterProductOption(linked)
                .sellingPrice(BigDecimal.TEN).active(active)
                .approvalStatus(OptionApprovalStatus.NOT_APPROVED).build();
    }

    // ---------------------------------------------------------------- onOptionCreated

    @Test
    void onOptionCreated_addsInactiveRowToEveryCell() {
        ProductListing draft = cell(1L, null);
        ProductListing onMarket = cell(2L, "COUPANG-99");
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(draft, onMarket));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of());

        sync.onOptionCreated(MASTER_ID, option(5L, "2개입"));

        // ⚠️ The core rule: switched off on BOTH the draft and the market-registered cell — an added option
        // never joins the next push payload on its own.
        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ProductListingOption::getActive).containsOnly(false);
        assertThat(saved.getAllValues()).extracting(ProductListingOption::getOptionName).containsOnly("2개입");
        assertThat(saved.getAllValues()).extracting(ProductListingOption::getSellingPrice)
                .containsOnly(BigDecimal.ZERO);      // placeholder, overwritten by the recalc below
        // 🔴 2609_22/D1: without the FK every propagated option would be born channel-only and be skipped by
        // propagation, price recalculation and the stock clamp for ever — silently.
        assertThat(saved.getAllValues())
                .allSatisfy(o -> assertThat(o.getMasterProductOption().getId()).isEqualTo(5L));

        // 2609_71: 구성품은 복사하지 않는다(FK 하나로 따라온다) — 셀마다 판매가만 다시 계산한다.
        verify(listingAssetService, times(1)).recalculateOptionPrices(draft);
        verify(listingAssetService, times(1)).recalculateOptionPrices(onMarket);
    }

    @Test
    void onOptionCreated_linkedRowExists_reusesRow_keepsActiveFlag() {
        // A deleted-then-re-added option: the row survived (switched off), so reuse it — re-adding must not
        // silently re-activate it. 2609_71: 그 행의 구성품은 FK 를 타고 새 items 를 곧바로 따른다.
        ProductListing cell = cell(1L, null);
        ProductListingOption existing = cellOption(50L, cell, "2개입", false, option(5L, "2개입"));
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection()))
                .willReturn(List.of(existing));

        sync.onOptionCreated(MASTER_ID, option(5L, "2개입"));

        verify(productListingOptionRepository, never()).save(any());        // no new row, active untouched
        verify(listingAssetService).recalculateOptionPrices(cell);
    }

    @Test
    void onOptionCreated_doesNotTouchOtherActiveOptions() {
        // 42's "at least one active option" only holds if propagation never switches existing options off.
        ProductListing cell = cell(1L, "COUPANG-99");
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection()))
                .willReturn(List.of(cellOption(50L, cell, "1개입", true, option(4L, "1개입"))));

        sync.onOptionCreated(MASTER_ID, option(5L, "2개입"));

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());       // only the new row
        assertThat(saved.getValue().getOptionName()).isEqualTo("2개입");
        assertThat(saved.getValue().getActive()).isFalse();
    }

    @Test
    void onOptionCreated_readsCellsAndOptionsOnceRegardlessOfCellCount() {
        // Query budget: the cell list and all their options are each read ONCE and matched in memory.
        List<ProductListing> cells = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> cell((long) i, null)).toList();
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(cells);
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of());

        sync.onOptionCreated(MASTER_ID, option(5L, "2개입"));

        verify(productListingRepository, times(1)).findByMasterProductId(MASTER_ID);
        verify(productListingOptionRepository, times(1)).findByProductListingIdIn(anyCollection());
        verify(productListingOptionRepository, never()).findByProductListingId(any());
    }

    // ---------------------------------------------------------------- onOptionRenamed

    @Test
    void onOptionRenamed_cascadesToLinkedCellOptionsOnly() {
        ProductListing cell = cell(1L, null);
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of(
                cellOption(50L, cell, "2개입", true, option(5L, "2개입")),
                cellOption(51L, cell, "1개입", true, option(4L, "1개입"))));

        sync.onOptionRenamed(MASTER_ID, 5L, "두개입");

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(50L);
        assertThat(saved.getValue().getOptionName()).isEqualTo("두개입");
    }

    @Test
    void onOptionRenamed_manualOverrideCellOption_isNotRenamed() {
        // 2609_22/D4: the channel named this option itself (the marketplace may already show that name), so a
        // master rename leaves it alone — [옵션명 일괄 적용] is what pulls it back.
        ProductListing cell = cell(1L, null);
        MasterProductOption master = option(5L, "2개입");
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of(
                cellOption(50L, cell, "채널이 붙인 이름", true, master).toBuilder()
                        .optionNameSource(GeneratedContentSource.MANUAL_OVERRIDE).build(),
                cellOption(51L, cell, "2개입", true, master)));   // AUTO (entity default)

        sync.onOptionRenamed(MASTER_ID, 5L, "두개입");

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(51L);     // only the AUTO row moved
        assertThat(saved.getValue().getOptionName()).isEqualTo("두개입");
    }

    @Test
    void onOptionRenamed_cellRenamedItself_isStillFoundByTheLink() {
        // 2609_22/D1 in one assertion: the cell's own name matches nothing any more, yet the FK finds it.
        ProductListing cell = cell(1L, null);
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of(
                cellOption(50L, cell, "완전히 다른 이름", true, option(5L, "2개입"))));

        sync.onOptionRenamed(MASTER_ID, 5L, "두개입");

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(50L);
        assertThat(saved.getValue().getOptionName()).isEqualTo("두개입");
    }

    @Test
    void onOptionRenamed_nameAlreadyOnThatCell_leavesRowAlone() {
        // Defensive: a MANUAL_OVERRIDE sibling (or a legacy duplicate) already carries the new name, and two
        // options with the same name in one cell are a marketplace error (Coupang itemName).
        ProductListing cell = cell(1L, null);
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of(
                cellOption(50L, cell, "2개입", true, option(5L, "2개입")),
                cellOption(51L, cell, "두개입", true, option(6L, "3개입"))));

        sync.onOptionRenamed(MASTER_ID, 5L, "두개입");

        verify(productListingOptionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- onOptionRemoved

    @Test
    void onOptionRemoved_deactivatesMatchedRowsWithoutDeletingAnything() {
        ProductListing draft = cell(1L, null);
        ProductListing onMarket = cell(2L, "COUPANG-99");
        given(productListingRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(draft, onMarket));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of(
                cellOption(50L, draft, "2개입", true, option(5L, "2개입")),
                cellOption(51L, draft, "1개입", true, option(4L, "1개입")),      // other option → untouched
                cellOption(52L, onMarket, "2개입", false, option(5L, "2개입")))); // already off → no re-save

        sync.onOptionRemoved(MASTER_ID, 5L);

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(50L);
        assertThat(saved.getValue().getActive()).isFalse();
        // 🔴 42: the row is kept — deactivation already keeps it out of the push payload.
        verify(productListingOptionRepository, never()).delete(any());
        verify(productListingOptionRepository, never()).deleteAll(any());
        // Remaining options' prices did not move.
        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }

    // ------------------------------------------------- onOptionComponentsChanged (2609_64)

    @Test
    void onOptionComponentsChanged_recalculatesPriceOfCellsCarryingTheOption() {
        // 2609_71: 마스터 옵션의 구성이 바뀌면 셀 옵션의 구성품도 FK 를 타고 저절로 바뀐다 — 복사할 줄이
        // 없으므로 남는 일은 바뀐 원가 합을 판매가에 반영하는 것뿐이다.
        ProductListing cell = cell(1L, "COUPANG-99");
        MasterProductOption option = option(5L, "2개입");
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection()))
                .willReturn(List.of(cellOption(50L, cell, "2개입", true, option)));

        sync.onOptionComponentsChanged(MASTER_ID, option);

        verify(listingAssetService).recalculateOptionPrices(cell);
        // 🔴 "the composition changed" is not "an option appeared" — no new cell option row here.
        verify(productListingOptionRepository, never()).save(any());
    }

    @Test
    void onOptionComponentsChanged_cellWithoutThatOption_isLeftAlone() {
        ProductListing cell = cell(1L, null);
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection()))
                .willReturn(List.of(cellOption(50L, cell, "채널전용", true)));

        sync.onOptionComponentsChanged(MASTER_ID, option(5L, "2개입"));

        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }

    // ---------------------------------------------------------------- syncStructure (propagation)

    @Test
    void syncStructure_createsMissingOptionInactive_andDeactivatesStaleLink() {
        ProductListing draft = cell(1L, null);
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(option(5L, "2개입")));
        // Linked to an option this master no longer has (a legacy row — the FK is normally SET NULL, D22).
        given(productListingOptionRepository.findByProductListingId(1L))
                .willReturn(List.of(cellOption(50L, draft, "옛옵션", true, option(9L, "옛옵션"))));

        sync.syncStructure(draft);

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, times(2)).save(saved.capture());
        ProductListingOption created = saved.getAllValues().get(0);
        assertThat(created.getOptionName()).isEqualTo("2개입");
        assertThat(created.getActive()).isFalse();
        assertThat(created.getMasterProductOption().getId()).isEqualTo(5L);
        ProductListingOption stale = saved.getAllValues().get(1);
        assertThat(stale.getId()).isEqualTo(50L);
        assertThat(stale.getActive()).isFalse();
        verify(listingAssetService).recalculateOptionPrices(draft);
    }

    @Test
    void syncStructure_channelOnlyOption_isLeftUntouched() {
        // 🔴 2609_22/D2: an option with no link is deliberately absent from the master, NOT an orphan.
        // Treating FK null as "gone from the master" would switch off every imported/channel-only option.
        ProductListing draft = cell(1L, null);
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(1L))
                .willReturn(List.of(cellOption(50L, draft, "채널전용", true)));

        sync.syncStructure(draft);

        verify(productListingOptionRepository, never()).save(any());
        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }

    @Test
    void syncStructure_activeStaleLinkOnMarketCell_isLeftUntouched() {
        // ⚠️ It is really on sale on Coupang; switching it off locally only desynchronises screen from market.
        ProductListing onMarket = cell(2L, "COUPANG-99");
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(2L))
                .willReturn(List.of(cellOption(50L, onMarket, "옛옵션", true, option(9L, "옛옵션"))));

        sync.syncStructure(onMarket);

        verify(productListingOptionRepository, never()).save(any());
        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }

    @Test
    void syncStructure_alreadyInSync_savesNothingAndSkipsRecalculation() {
        ProductListing draft = cell(1L, null);
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(option(5L, "2개입")));
        // Renamed by the channel — still in sync, because the link (not the name) is what is compared.
        given(productListingOptionRepository.findByProductListingId(1L))
                .willReturn(List.of(cellOption(50L, draft, "채널이 붙인 이름", true, option(5L, "2개입"))));

        sync.syncStructure(draft);

        verify(productListingOptionRepository, never()).save(any());
        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }
}
