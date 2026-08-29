package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
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
 * one is switched off (never deleted), and propagation reconciles one cell — leaving an active orphan of a
 * market-registered cell alone.
 */
@ExtendWith(MockitoExtension.class)
class MasterOptionChannelSyncTest {

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;
    @Mock private MasterProductOptionItemRepository masterProductOptionItemRepository;
    @Mock private ListingAssetService listingAssetService;
    @Mock private OptionQuantitySync optionQuantitySync;      // must stay unused — see the idempotency test
    @InjectMocks private MasterOptionChannelSyncImpl sync;

    private static final Long MASTER_ID = 10L;
    private static final MasterProduct MASTER = MasterProduct.builder().id(MASTER_ID).name("마스터").build();

    // ---------------------------------------------------------------- fixtures

    /** platformProductId null = DRAFT cell; non-null = market-registered. */
    private ProductListing cell(Long id, String platformProductId) {
        return ProductListing.builder().id(id).platform("COUPANG").name("셀-" + id)
                .masterProduct(MASTER).platformProductId(platformProductId).build();
    }

    private MasterProductOption option(Long id, String name) {
        return MasterProductOption.builder().id(id).name(name).masterProduct(MASTER).build();
    }

    private MasterProductOptionItem item(Long productId, int quantity) {
        return MasterProductOptionItem.builder()
                .product(Product.builder().id(productId).build()).quantity(quantity).build();
    }

    private ProductListingOption cellOption(Long id, ProductListing cell, String name, boolean active) {
        return ProductListingOption.builder().id(id).productListing(cell).optionName(name)
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
        given(masterProductOptionItemRepository.findByOptionId(5L)).willReturn(List.of(item(7L, 2)));

        sync.onOptionCreated(MASTER_ID, option(5L, "2개입"));

        // ⚠️ The core rule: switched off on BOTH the draft and the market-registered cell — an added option
        // never joins the next push payload on its own.
        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ProductListingOption::getActive).containsOnly(false);
        assertThat(saved.getAllValues()).extracting(ProductListingOption::getOptionName).containsOnly("2개입");
        assertThat(saved.getAllValues()).extracting(ProductListingOption::getSellingPrice)
                .containsOnly(BigDecimal.ZERO);      // placeholder, overwritten by the recalc below

        // BOM lines copied per cell, then the real price derived once per cell.
        ArgumentCaptor<ProductListingProduct> lines = ArgumentCaptor.forClass(ProductListingProduct.class);
        verify(productListingProductRepository, times(2)).save(lines.capture());
        assertThat(lines.getAllValues()).extracting(ProductListingProduct::getQuantity).containsOnly(2);
        verify(listingAssetService, times(1)).recalculateOptionPrices(draft);
        verify(listingAssetService, times(1)).recalculateOptionPrices(onMarket);
    }

    @Test
    void onOptionCreated_sameNameExists_rebuildsLinesOnly_keepsActiveFlag() {
        // A deleted-then-re-added option: the row survived (switched off), so reuse it — but its BOM is
        // stale, and re-adding must not silently re-activate it either.
        ProductListing cell = cell(1L, null);
        ProductListingOption existing = cellOption(50L, cell, "2개입", false);
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection()))
                .willReturn(List.of(existing));
        given(masterProductOptionItemRepository.findByOptionId(5L)).willReturn(List.of(item(7L, 3)));

        sync.onOptionCreated(MASTER_ID, option(5L, "2개입"));

        verify(productListingOptionRepository, never()).save(any());        // no new row, active untouched
        // ⚠️ Replace, never merge: syncLines would only touch products present on both sides and leave the
        // deleted option's stale composition (→ wrong cost → wrong price) behind.
        verify(productListingProductRepository).deleteByProductListingOptionId(50L);
        verify(optionQuantitySync, never()).syncLines(any(), any());
        ArgumentCaptor<ProductListingProduct> lines = ArgumentCaptor.forClass(ProductListingProduct.class);
        verify(productListingProductRepository).save(lines.capture());
        assertThat(lines.getValue().getQuantity()).isEqualTo(3);
        verify(listingAssetService).recalculateOptionPrices(cell);
    }

    @Test
    void onOptionCreated_doesNotTouchOtherActiveOptions() {
        // 42's "at least one active option" only holds if propagation never switches existing options off.
        ProductListing cell = cell(1L, "COUPANG-99");
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection()))
                .willReturn(List.of(cellOption(50L, cell, "1개입", true)));
        given(masterProductOptionItemRepository.findByOptionId(5L)).willReturn(List.of(item(7L, 2)));

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
        given(masterProductOptionItemRepository.findByOptionId(5L)).willReturn(List.of());

        sync.onOptionCreated(MASTER_ID, option(5L, "2개입"));

        verify(productListingRepository, times(1)).findByMasterProductId(MASTER_ID);
        verify(productListingOptionRepository, times(1)).findByProductListingIdIn(anyCollection());
        verify(productListingOptionRepository, never()).findByProductListingId(any());
    }

    // ---------------------------------------------------------------- onOptionRenamed

    @Test
    void onOptionRenamed_cascadesToMatchedCellOptionsOnly() {
        ProductListing cell = cell(1L, null);
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of(
                cellOption(50L, cell, "2개입", true), cellOption(51L, cell, "1개입", true)));

        sync.onOptionRenamed(MASTER_ID, "2개입", "두개입");

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(50L);
        assertThat(saved.getValue().getOptionName()).isEqualTo("두개입");
    }

    @Test
    void onOptionRenamed_sameName_savesNothing() {
        sync.onOptionRenamed(MASTER_ID, "2개입", "2개입");

        verify(productListingRepository, never()).findByMasterProductId(any());
        verify(productListingOptionRepository, never()).save(any());
    }

    @Test
    void onOptionRenamed_newNameAlreadyPresentOnCell_leavesRowAlone() {
        // Defensive: unreachable through the master-level uniqueness guard, but a legacy channel row may
        // already carry the new name — renaming would create a duplicate, non-deterministic match key.
        ProductListing cell = cell(1L, null);
        given(productListingRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of(cell));
        given(productListingOptionRepository.findByProductListingIdIn(anyCollection())).willReturn(List.of(
                cellOption(50L, cell, "2개입", true), cellOption(51L, cell, "두개입", true)));

        sync.onOptionRenamed(MASTER_ID, "2개입", "두개입");

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
                cellOption(50L, draft, "2개입", true),
                cellOption(51L, draft, "1개입", true),          // other option → untouched
                cellOption(52L, onMarket, "2개입", false)));    // already off → no re-save

        sync.onOptionRemoved(MASTER_ID, "2개입");

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(50L);
        assertThat(saved.getValue().getActive()).isFalse();
        // 🔴 42: the row and its BOM lines are kept — deactivation already keeps it out of the push payload.
        verify(productListingOptionRepository, never()).delete(any());
        verify(productListingOptionRepository, never()).deleteAll(any());
        verify(productListingProductRepository, never()).deleteByProductListingOptionId(any());
        // Remaining options' prices did not move.
        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }

    // ---------------------------------------------------------------- syncStructure (propagation)

    @Test
    void syncStructure_createsMissingOptionInactive_andDeactivatesDraftOrphan() {
        ProductListing draft = cell(1L, null);
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(option(5L, "2개입")));
        given(productListingOptionRepository.findByProductListingId(1L))
                .willReturn(List.of(cellOption(50L, draft, "옛옵션", true)));   // orphan: master no longer has it
        given(masterProductOptionItemRepository.findByOptionId(5L)).willReturn(List.of(item(7L, 2)));

        sync.syncStructure(draft);

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository, times(2)).save(saved.capture());
        ProductListingOption created = saved.getAllValues().get(0);
        assertThat(created.getOptionName()).isEqualTo("2개입");
        assertThat(created.getActive()).isFalse();
        ProductListingOption orphan = saved.getAllValues().get(1);
        assertThat(orphan.getId()).isEqualTo(50L);
        assertThat(orphan.getActive()).isFalse();
        verify(listingAssetService).recalculateOptionPrices(draft);
    }

    @Test
    void syncStructure_activeOrphanOnMarketCell_isLeftUntouched() {
        // ⚠️ It is really on sale on Coupang; switching it off locally only desynchronises screen from market.
        ProductListing onMarket = cell(2L, "COUPANG-99");
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID)).willReturn(List.of());
        given(productListingOptionRepository.findByProductListingId(2L))
                .willReturn(List.of(cellOption(50L, onMarket, "옛옵션", true)));

        sync.syncStructure(onMarket);

        verify(productListingOptionRepository, never()).save(any());
        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }

    @Test
    void syncStructure_alreadyInSync_savesNothingAndSkipsRecalculation() {
        ProductListing draft = cell(1L, null);
        given(masterProductOptionRepository.findByMasterProductId(MASTER_ID))
                .willReturn(List.of(option(5L, "2개입")));
        given(productListingOptionRepository.findByProductListingId(1L))
                .willReturn(List.of(cellOption(50L, draft, "2개입", true)));

        sync.syncStructure(draft);

        verify(productListingOptionRepository, never()).save(any());
        verify(productListingProductRepository, never()).save(any());
        verify(listingAssetService, never()).recalculateOptionPrices(any());
    }
}
