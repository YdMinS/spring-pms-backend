package com.pms.service.price;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.Platform;
import com.pms.domain.PriceChangeLog;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.PriceTargetType;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.request.PriceOverrideRequest;
import com.pms.dto.response.PriceOverrideResult;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.listing.CellBomResolver;
import com.pms.repository.ProductListingRepository;
import com.pms.service.ListingAssetService;
import com.pms.service.PriceCalculator;
import com.pms.service.listing.ListingChannel;
import com.pms.service.listing.ListingChannelResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 판매가 직접 입력(FEATURE_2609_42 / 01 · PLAN D1·D2·D4·D10·D24).
 *
 * <p>🔴 이 클래스에서 가장 중요한 시험은 {@code override_keepsPriceSourceAuto} 다. 이 경로가 옵션을
 * {@code MANUAL_OVERRIDE} 로 표시하는 순간(= {@code setOptionPrices} 재사용) 그 옵션은 다음 재계산부터
 * 영구 제외되어 「이번 한 번만」이라는 이 기능의 정의가 뒤집힌다.</p>
 *
 * <p>⚠️ {@code self} 프록시는 실제 서비스 인스턴스로 배선한다(setUp) — 단위테스트가 {@code REQUIRES_NEW}
 * 자체를 검증할 수는 없고, 루프의 집계·격리만 본다({@code RepricingPushTest} 와 같은 자세).</p>
 *
 * <p>⚠️ {@link PriceHistoryRecorder} 는 <b>실제 인스턴스</b>다(리포지토리만 mock) — 이력이 "호출됐다"가
 * 아니라 실제로 어떤 행이 쓰이는지를 봐야 사유·old/new 가 증명된다.</p>
 */
@ExtendWith(MockitoExtension.class)
class RepricingOverrideTest {

    private static final Long SELLER_ID = 7L;
    private static final Long CELL_ID = 100L;
    private static final LocalDateTime PUSHED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private CellBomResolver cellBomResolver;
    @Mock private PriceCalculator priceCalculator;
    @Mock private ListingAssetService listingAssetService;
    @Mock private ListingChannelResolver channelResolver;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ListingChannel channel;
    @Mock private PriceChangeLogRepository priceChangeLogRepository;

    private RepricingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RepricingServiceImpl(productListingRepository, productListingOptionRepository,
                cellBomResolver, priceCalculator, listingAssetService, channelResolver,
                marketplaceAccountRepository, new PriceHistoryRecorder(priceChangeLogRepository));
        ReflectionTestUtils.setField(service, "self", service);
    }

    // ---------------------------------------------------------------- fixtures

    private final Seller seller = Seller.builder().id(SELLER_ID).sellerName("행복상회").build();

    private ProductListing cell(Long id, ListingStatus status) {
        return ProductListing.builder().id(id).platform(Platform.COUPANG).name("쿠팡 셀")
                .status(status).platformProductId("SP-" + id).seller(seller).build();
    }

    /** 입력 대상 옵션 — 판매중 셀의 AUTO 가격. 마켓 가격은 이미 밀려 있는 상태. */
    private ProductListingOption option(Long id, ProductListing cell, String sellingPrice) {
        return ProductListingOption.builder().id(id).productListing(cell).optionName("옵션" + id)
                .platformOptionId("V-" + id)
                .sellingPrice(new BigDecimal(sellingPrice))
                .marketPrice(new BigDecimal(sellingPrice))
                .marketPriceAt(PUSHED_AT)
                .priceSource(GeneratedContentSource.AUTO)
                .build();
    }

    /** 직접 지정가 옵션 — 가격을 사람이 소유한다(2609_19). 판매중 셀의 정상 옵션이다. */
    private ProductListingOption manualOption(ProductListing cell) {
        return option(10L, cell, "12000.00").toBuilder()
                .priceSource(GeneratedContentSource.MANUAL_OVERRIDE).build();
    }

    private static PriceOverrideRequest.Item item(Long optionId, String price) {
        return new PriceOverrideRequest.Item(optionId, new BigDecimal(price));
    }

    /** 옵션 로드 + 부모 셀 스코프 확인을 세운다(채널·계정은 이 경로에서 해석되지 않는다). */
    private void givenLoadable(List<ProductListingOption> options, ProductListing... cells) {
        given(productListingOptionRepository.findWithConfigByIdIn(anyCollection())).willReturn(options);
        for (ProductListing cell : cells) {
            given(productListingRepository.findScopedById(cell.getId())).willReturn(Optional.of(cell));
        }
    }

    private ProductListingOption savedOption() {
        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());
        return saved.getValue();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void override_savesEnteredPrice() {
        ProductListing cell = cell(CELL_ID, ListingStatus.SELLING);
        givenLoadable(List.of(option(10L, cell, "12000.00")), cell);

        PriceOverrideResult result = service.override(List.of(item(10L, "13500.00")));

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.skipped()).isEmpty();
        assertThat(result.failed()).isEmpty();
        assertThat(savedOption().getSellingPrice()).isEqualByComparingTo("13500.00");
    }

    /** 🔴 D2 회귀 — 이게 깨지면 입력한 옵션이 다음 재계산부터 영구 제외되어 기능이 스스로를 무력화한다. */
    @Test
    void override_keepsPriceSourceAuto() {
        ProductListing cell = cell(CELL_ID, ListingStatus.SELLING);
        givenLoadable(List.of(option(10L, cell, "12000.00")), cell);

        service.override(List.of(item(10L, "13500.00")));

        assertThat(savedOption().getPriceSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    /** 🔴 D10 — market_price 가 그대로여야 다음 조회에서 「아직 안 밀림」이 켜진다. */
    @Test
    void override_doesNotTouchMarketPrice() {
        ProductListing cell = cell(CELL_ID, ListingStatus.SELLING);
        givenLoadable(List.of(option(10L, cell, "12000.00")), cell);

        service.override(List.of(item(10L, "13500.00")));

        ProductListingOption saved = savedOption();
        assertThat(saved.getMarketPrice()).isEqualByComparingTo("12000.00");
        assertThat(saved.getMarketPriceAt()).isEqualTo(PUSHED_AT);
        // market_price(12000) != selling_price(13500) → 다음 조회에서 pendingPush = true
        assertThat(saved.getMarketPrice()).isNotEqualByComparingTo(saved.getSellingPrice());
    }

    /** 🔴 D1 — 이 경로는 네트워크를 쓰지 않는다. 채널·계정 해석조차 하지 않는다. */
    @Test
    void override_callsNoMarketplace() {
        ProductListing cell = cell(CELL_ID, ListingStatus.SELLING);
        givenLoadable(List.of(option(10L, cell, "12000.00")), cell);

        service.override(List.of(item(10L, "13500.00")));

        verifyNoInteractions(channel);
        verifyNoInteractions(channelResolver);
        verifyNoInteractions(marketplaceAccountRepository);
    }

    /** 🔴 2609_43 D1(2609_42 D4 번복) — 사람이 소유한 가격을 사람이 바꾸는 것이라 막지 않는다. */
    @Test
    void override_manualOptionApplied() {
        ProductListing cell = cell(CELL_ID, ListingStatus.SELLING);
        givenLoadable(List.of(manualOption(cell)), cell);

        PriceOverrideResult result = service.override(List.of(item(10L, "13500.00")));

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.skipped()).isEmpty();
        assertThat(result.failed()).isEmpty();
        assertThat(savedOption().getSellingPrice()).isEqualByComparingTo("13500.00");
    }

    /**
     * 🔴 2609_43 D2 회귀 — 직접 지정가 옵션은 저장 후에도 직접 지정가로 남아야 한다. 이 경로가
     * {@code priceSource} 를 쓰기 시작하면(= AUTO 로 되돌리면) 2609_19 가 통째로 무너진다.
     */
    @Test
    void override_manualOption_keepsManualPriceSource() {
        ProductListing cell = cell(CELL_ID, ListingStatus.SELLING);
        givenLoadable(List.of(manualOption(cell)), cell);

        service.override(List.of(item(10L, "13500.00")));

        assertThat(savedOption().getPriceSource()).isEqualTo(GeneratedContentSource.MANUAL_OVERRIDE);
    }

    @Test
    void override_notSellingCellSkipped() {
        ProductListing draft = cell(CELL_ID, ListingStatus.DRAFT);
        givenLoadable(List.of(option(10L, draft, "12000.00")), draft);

        PriceOverrideResult result = service.override(List.of(item(10L, "13500.00")));

        assertThat(result.applied()).isZero();
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).reason()).isEqualTo("판매중인 상품이 아님");
        verify(productListingOptionRepository, never()).save(any());
    }

    /** 🔴 마켓 식별자는 전송에만 필요하다 — 아직 등록 전인 셀도 가격은 정할 수 있다. */
    @Test
    void override_missingMarketIdApplied() {
        ProductListing cell = cell(CELL_ID, ListingStatus.SELLING);
        ProductListingOption unregistered = option(10L, cell, "12000.00").toBuilder()
                .platformOptionId(null).build();
        givenLoadable(List.of(unregistered), cell);

        PriceOverrideResult result = service.override(List.of(item(10L, "13500.00")));

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.skipped()).isEmpty();
        assertThat(savedOption().getSellingPrice()).isEqualByComparingTo("13500.00");
    }

    /** D5 — 사람이 친 가격도 이력에 남아야 "누가 왜 이 값을 넣었나"를 되짚을 수 있다. */
    @Test
    void override_recordsPriceHistory() {
        ProductListing cell = cell(CELL_ID, ListingStatus.SELLING);
        givenLoadable(List.of(option(10L, cell, "12000.00")), cell);

        service.override(List.of(item(10L, "13500.00")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PriceChangeLog>> rows = ArgumentCaptor.forClass(List.class);
        verify(priceChangeLogRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        PriceChangeLog row = rows.getValue().get(0);
        assertThat(row.getTargetType()).isEqualTo(PriceTargetType.LISTING_SELLING);
        assertThat(row.getReason()).isEqualTo(PriceChangeReason.MANUAL);
        assertThat(row.getOldPrice()).isEqualByComparingTo("12000.00");
        assertThat(row.getNewPrice()).isEqualByComparingTo("13500.00");
    }

    /** D24 — 옵션 엔티티에는 {@code @TenantId} 가 없다. 부모 셀로 스코프하지 않으면 남의 가격을 바꾼다. */
    @Test
    void override_foreignTenantOption_404() {
        ProductListing foreignCell = ProductListing.builder().id(999L).platform(Platform.COUPANG)
                .name("남의 셀").status(ListingStatus.SELLING).platformProductId("SP-999")
                .seller(Seller.builder().id(99L).sellerName("남의 상회").build()).build();
        given(productListingOptionRepository.findWithConfigByIdIn(anyCollection()))
                .willReturn(List.of(option(10L, foreignCell, "12000.00")));
        given(productListingRepository.findScopedById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.override(List.of(item(10L, "13500.00"))))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(productListingOptionRepository, never()).save(any());
        verifyNoInteractions(priceChangeLogRepository);
    }
}
