package com.pms.service.price;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.response.RepricePushResult;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 마켓 반영(FEATURE_2609_39 / 02 · PLAN D7 ②·D8·D22·D24).
 *
 * <p>🔴 이 클래스에서 가장 중요한 시험은 {@code testPushKeepsPriceSourceAuto} 다. 전송 경로가 옵션을
 * {@code MANUAL_OVERRIDE} 로 표시하는 순간(= {@code setOptionPrices} 재사용) 그 옵션은 다음 재계산부터
 * 영구 제외되어 이 기능이 스스로를 무력화한다.</p>
 *
 * <p>⚠️ {@code self} 프록시는 실제 서비스 인스턴스로 배선한다(setUp) — 단위테스트가 {@code REQUIRES_NEW}
 * 자체를 검증할 수는 없고, 루프의 집계·격리만 본다({@code MasterPropagationServiceTest} 와 같은 자세).</p>
 */
@ExtendWith(MockitoExtension.class)
class RepricingPushTest {

    private static final Long SELLER_ID = 7L;
    private static final Long CELL_ID = 100L;

    @Mock private ProductListingRepository productListingRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private ProductListingProductRepository productListingProductRepository;
    @Mock private PriceCalculator priceCalculator;
    @Mock private ListingAssetService listingAssetService;
    @Mock private ListingChannelResolver channelResolver;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private PriceHistoryRecorder priceHistoryRecorder;
    @Mock private ListingChannel channel;

    private RepricingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RepricingServiceImpl(productListingRepository, productListingOptionRepository,
                productListingProductRepository, priceCalculator, listingAssetService, channelResolver,
                marketplaceAccountRepository, priceHistoryRecorder);
        ReflectionTestUtils.setField(service, "self", service);
    }

    // ---------------------------------------------------------------- fixtures

    private final Seller seller = Seller.builder().id(SELLER_ID).sellerName("행복상회").build();

    private ProductListing cell(Long id, Seller owner) {
        return ProductListing.builder().id(id).platform(Platform.COUPANG).name("쿠팡 셀")
                .status(ListingStatus.SELLING).platformProductId("SP-" + id).seller(owner).build();
    }

    /** 전송 대상 옵션 — 식별자 있고 AUTO 가격. */
    private ProductListingOption option(Long id, ProductListing cell, String sellingPrice) {
        return ProductListingOption.builder().id(id).productListing(cell).optionName("옵션" + id)
                .platformOptionId("V-" + id)
                .sellingPrice(new BigDecimal(sellingPrice))
                .priceSource(GeneratedContentSource.AUTO)
                .build();
    }

    private MarketplaceAccount account(Seller owner) {
        return MarketplaceAccount.builder().id(1L).seller(owner).platform(Platform.COUPANG)
                .isActive(true).build();
    }

    /** 옵션 로드 + 부모 셀 스코프 확인 + 어댑터 해석을 한 번에 세운다. */
    private void givenLoadable(List<ProductListingOption> options, ProductListing... cells) {
        given(productListingOptionRepository.findWithConfigByIdIn(anyCollection())).willReturn(options);
        for (ProductListing cell : cells) {
            given(productListingRepository.findScopedById(cell.getId())).willReturn(Optional.of(cell));
        }
        given(channelResolver.resolveOptional(Platform.COUPANG)).willReturn(Optional.of(channel));
    }

    // ---------------------------------------------------------------- tests

    @Test
    void testPushUpdatesMarketPriceOnSuccess() {
        ProductListing cell = cell(CELL_ID, seller);
        ProductListingOption option = option(10L, cell, "12000.00");
        givenLoadable(List.of(option), cell);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, Platform.COUPANG))
                .willReturn(Optional.of(account(seller)));

        RepricePushResult result = service.push(List.of(10L));

        assertThat(result.pushed()).isEqualTo(1);
        assertThat(result.skipped()).isEmpty();
        assertThat(result.failed()).isEmpty();
        assertThat(result.stopped()).isFalse();

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());
        assertThat(saved.getValue().getMarketPrice()).isEqualByComparingTo("12000.00");
        assertThat(saved.getValue().getMarketPriceAt()).isNotNull();
        // 보낸 값 == 저장한 값(D15 의 「아직 안 밀림」 판정이 어긋나지 않으려면 정확히 같아야 한다).
        verify(channel).updateOptionPrice(eq(option), eq(new BigDecimal("12000.00")), any());
    }

    /** 🔴 D8 회귀 — 이게 깨지면 전송된 옵션이 다음 재계산부터 영구 제외되어 기능이 죽는다. */
    @Test
    void testPushKeepsPriceSourceAuto() {
        ProductListing cell = cell(CELL_ID, seller);
        givenLoadable(List.of(option(10L, cell, "12000.00")), cell);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, Platform.COUPANG))
                .willReturn(Optional.of(account(seller)));

        service.push(List.of(10L));

        ArgumentCaptor<ProductListingOption> saved = ArgumentCaptor.forClass(ProductListingOption.class);
        verify(productListingOptionRepository).save(saved.capture());
        assertThat(saved.getValue().getPriceSource()).isEqualTo(GeneratedContentSource.AUTO);
    }

    @Test
    void testPushDoesNotSaveOnFailure() {
        ProductListing cell = cell(CELL_ID, seller);
        ProductListingOption option = option(10L, cell, "12000.00");
        givenLoadable(List.of(option), cell);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, Platform.COUPANG))
                .willReturn(Optional.of(account(seller)));
        willThrow(new IllegalStateException("쿠팡 가격변경 실패: 승인 대기중"))
                .given(channel).updateOptionPrice(any(), any(), any());

        RepricePushResult result = service.push(List.of(10L));

        assertThat(result.pushed()).isZero();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).optionId()).isEqualTo(10L);
        assertThat(result.failed().get(0).message()).contains("승인 대기중");
        // 실패한 옵션의 market_price 는 그대로여야 다음 조회에서 「아직 안 밀림」으로 남는다.
        verify(productListingOptionRepository, never()).save(any());
    }

    @Test
    void testPushSkipsManualAndUnregistered() {
        ProductListing cell = cell(CELL_ID, seller);
        ProductListingOption manual = option(10L, cell, "12000.00").toBuilder()
                .priceSource(GeneratedContentSource.MANUAL_OVERRIDE).build();
        ProductListingOption unregistered = option(11L, cell, "9000.00").toBuilder()
                .platformOptionId(null).build();
        given(productListingOptionRepository.findWithConfigByIdIn(anyCollection()))
                .willReturn(List.of(manual, unregistered));
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));

        RepricePushResult result = service.push(List.of(10L, 11L));

        assertThat(result.pushed()).isZero();
        assertThat(result.skipped()).hasSize(2);
        assertThat(result.skipped()).extracting(RepricePushResult.SkippedOption::optionId)
                .containsExactly(10L, 11L);
        verifyNoInteractions(channel);
        verify(productListingOptionRepository, never()).save(any());
    }

    /**
     * 🔴 회귀 — 직접 입력(2609_42)이 제외 규칙을 {@code Purpose} 로 나눴다. 전송 전용 사유인 「마켓 옵션
     * 식별자 없음」이 {@code push} 에서는 그대로 살아 있어야 한다(사유 문장까지 그대로).
     */
    @Test
    void testPushStillSkipsMissingMarketId() {
        ProductListing cell = cell(CELL_ID, seller);
        ProductListingOption unregistered = option(11L, cell, "9000.00").toBuilder()
                .platformOptionId(null).build();
        given(productListingOptionRepository.findWithConfigByIdIn(anyCollection()))
                .willReturn(List.of(unregistered));
        given(productListingRepository.findScopedById(CELL_ID)).willReturn(Optional.of(cell));

        RepricePushResult result = service.push(List.of(11L));

        assertThat(result.pushed()).isZero();
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).reason()).isEqualTo("마켓 옵션 식별자 없음");
        verifyNoInteractions(channel);
    }

    @Test
    void testPushStopsOnRateLimit() {
        ProductListing cell = cell(CELL_ID, seller);
        Instant retryAfter = Instant.parse("2026-09-13T10:00:00Z");
        givenLoadable(List.of(option(10L, cell, "12000.00"), option(11L, cell, "9000.00"),
                option(12L, cell, "8000.00")), cell);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, Platform.COUPANG))
                .willReturn(Optional.of(account(seller)));
        // 두 번째 옵션에서 쿨다운을 만난다(인자 매칭 대신 호출 순서로 고정).
        willDoNothing().willThrow(new CoupangRateLimitedException(retryAfter))
                .given(channel).updateOptionPrice(any(), any(), any());

        RepricePushResult result = service.push(List.of(10L, 11L, 12L));

        assertThat(result.stopped()).isTrue();
        assertThat(result.retryAfter()).isEqualTo(retryAfter);
        assertThat(result.pushed()).isEqualTo(1);           // 1번째 결과는 남는다(마켓은 롤백되지 않는다)
        assertThat(result.failed()).isEmpty();              // 429 는 그 옵션의 실패가 아니다
        verify(channel, times(2)).updateOptionPrice(any(), any(), any());   // 3번째는 호출되지 않았다
        verify(productListingOptionRepository, times(1)).save(any());
    }

    /** D22 — 계정 하나가 없다고 요청 전체를 세우면 「옵션마다 개별 커밋」 계약이 무의미해진다. */
    @Test
    void testPushFailsOptionWhenAccountMissing() {
        Seller other = Seller.builder().id(8L).sellerName("계정없는상회").build();
        ProductListing cellWithAccount = cell(CELL_ID, seller);
        ProductListing cellWithoutAccount = cell(200L, other);
        givenLoadable(List.of(option(10L, cellWithoutAccount, "9000.00"),
                option(11L, cellWithAccount, "12000.00")), cellWithAccount, cellWithoutAccount);
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(8L, Platform.COUPANG))
                .willReturn(Optional.empty());
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, Platform.COUPANG))
                .willReturn(Optional.of(account(seller)));

        RepricePushResult result = service.push(List.of(10L, 11L));

        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).optionId()).isEqualTo(10L);
        assertThat(result.pushed()).isEqualTo(1);           // 나머지 옵션은 정상 전송
        verify(channel, times(1)).updateOptionPrice(any(), any(), any());
    }

    /** D24 — 옵션 엔티티에는 {@code @TenantId} 가 없다. 부모 셀로 스코프하지 않으면 남의 가격을 바꾼다. */
    @Test
    void testPushRejectsOptionOfAnotherTenant() {
        ProductListing foreignCell = cell(999L, Seller.builder().id(99L).sellerName("남의 상회").build());
        given(productListingOptionRepository.findWithConfigByIdIn(anyCollection()))
                .willReturn(List.of(option(10L, foreignCell, "12000.00")));
        given(productListingRepository.findScopedById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.push(List.of(10L)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(channel);
        verify(productListingOptionRepository, never()).save(any());
    }
}
