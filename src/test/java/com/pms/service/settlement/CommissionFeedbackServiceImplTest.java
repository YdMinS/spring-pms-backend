package com.pms.service.settlement;

import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.PriceChangeLog;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.PriceTargetType;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import com.pms.dto.request.CommissionApplyRequest;
import com.pms.dto.response.CommissionApplyResponse;
import com.pms.dto.response.CommissionSuggestionResponse;
import com.pms.dto.response.CommissionSuggestionView;
import com.pms.exception.BusinessException;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.price.PriceHistoryRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실측 수수료율 피드백 — 제안 집계 + 확정 반영 (FEATURE_2609_30 / 06 · PLAN D16).
 *
 * <p>🔴 {@link #applyDoesNotTouchSellingPrice} 가 D16 의 유일한 보증이다: 수수료율이 바뀌어도 셀 판매가는
 * 움직이지 않는다. 자동 파급이 생기면 이 테스트가 먼저 깨져야 한다.
 */
@ExtendWith(MockitoExtension.class)
class CommissionFeedbackServiceImplTest {

    private static final BigDecimal VAT = new BigDecimal("0.1");
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Mock private SettlementLineRepository settlementLineRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private MasterChannelConfigService masterChannelConfigService;
    @Mock private PriceChangeLogRepository priceChangeLogRepository;
    /** 🔴 서비스에 주입하지 않는다 — 판매가를 쓸 수 있는 의존성이 없다는 것 자체가 D16 의 보증이다. */
    @Mock private ProductListingOptionRepository listingOptionRepository;

    private CommissionFeedbackServiceImpl service;

    /** 카테고리 A: 기준표 5% (실측 10.6%와 크게 어긋난 상태). */
    private final PlatformCategory kimchi = PlatformCategory.builder()
            .id(11L).platform(Platform.COUPANG).code("1001").name("김치")
            .commissionRate(new BigDecimal("0.05")).build();
    private final ProductListing kimchiCell = ProductListing.builder().id(3L).name("행복 김치").build();

    /** 카테고리 B: 같은 조건의 다른 카테고리 — "요청한 것만 바꾼다"를 보기 위한 두 번째 후보. */
    private final PlatformCategory sauce = PlatformCategory.builder()
            .id(22L).platform(Platform.COUPANG).code("2002").name("소스")
            .commissionRate(new BigDecimal("0.05")).build();
    private final ProductListing sauceCell = ProductListing.builder().id(4L).name("행복 소스").build();

    @BeforeEach
    void setUp() {
        service = new CommissionFeedbackServiceImpl(settlementLineRepository, platformCategoryRepository,
                masterChannelConfigService, new PriceHistoryRecorder(priceChangeLogRepository), VAT);
    }

    // ── 집계 ────────────────────────────────────────────────────────────

    @Test
    void actualRatioIsWeightedNotArithmetic() {
        // 1,000원 × 20% + 1,000,000원 × 10% → 가중평균 ≈ 10.01% (산술평균 15% 가 아니다).
        givenLines(List.of(
                line(kimchiCell, "1000", "200", "0"),
                line(kimchiCell, "1000000", "100000", "0")));
        givenCategory(kimchiCell, kimchi);

        CommissionSuggestionView view = only(service.suggestions(null, FROM, TO, 2).suggestions());

        assertThat(view.measuredRatio()).isEqualByComparingTo("0.1001");
        assertThat(view.measuredRatio()).isNotEqualByComparingTo("0.15");
        assertThat(view.samples()).isEqualTo(2);
        // 기준표 5% 는 부가세를 얹어 5.5% 로 비교한다 — 기준을 맞추지 않으면 부가세가 차이로 둔갑한다.
        assertThat(view.currentRatio()).isEqualByComparingTo("0.055");
        assertThat(view.gap()).isEqualByComparingTo("0.0451");
    }

    @Test
    void excludesCategoriesBelowMinSamples() {
        givenLines(sales(3));
        givenCategory(kimchiCell, kimchi);

        CommissionSuggestionResponse response = service.suggestions(null, FROM, TO, 5);

        assertThat(response.suggestions()).isEmpty();
        assertThat(response.minSamples()).isEqualTo(5);
    }

    @Test
    void excludesRefundLines() {
        // 환불 라인의 수수료는 환급이라 비율의 의미가 반대다 — 섞이면 실측이 통째로 어긋난다.
        List<SettlementLine> lines = new ArrayList<>(sales(5));
        lines.add(line(kimchiCell, "100000", "50000", "5000").toBuilder()
                .saleType(SaleType.REFUND).build());
        givenLines(lines);
        givenCategory(kimchiCell, kimchi);

        CommissionSuggestionView view = only(service.suggestions(null, FROM, TO, null).suggestions());

        assertThat(view.samples()).isEqualTo(5);
        assertThat(view.measuredRatio()).isEqualByComparingTo("0.1166");
        assertThat(view.measuredRate()).isEqualByComparingTo("0.106");
    }

    @Test
    void nullCurrentRateGoesToSeedingSection() {
        PlatformCategory unseeded = kimchi.toBuilder().commissionRate(null).build();
        givenLines(sales(5));
        givenCategory(kimchiCell, unseeded);

        CommissionSuggestionResponse response = service.suggestions(null, FROM, TO, null);

        assertThat(response.suggestions()).isEmpty();
        assertThat(response.seedingGaps()).singleElement().satisfies(view -> {
            assertThat(view.platformCategoryId()).isEqualTo(11L);
            assertThat(view.currentRate()).isNull();
            assertThat(view.gap()).isNull();
            assertThat(view.suggestedRate()).isEqualByComparingTo("0.11");
        });
    }

    // ── 확정 반영 ────────────────────────────────────────────────────────

    @Test
    void applyUpdatesOnlyRequestedCategories() {
        givenTwoCategories();
        given(platformCategoryRepository.findById(11L)).willReturn(Optional.of(kimchi));

        CommissionApplyResponse response = service.apply(request(11L, "0.106"));

        assertThat(response.updated()).isEqualTo(1);
        verify(platformCategoryRepository, times(1)).save(any());
        verify(platformCategoryRepository, never()).findById(eq(22L));
    }

    @Test
    void applyRecordsPriceHistory() {
        givenTwoCategories();
        given(platformCategoryRepository.findById(11L)).willReturn(Optional.of(kimchi));

        service.apply(request(11L, "0.106"));

        // 🔴 새 이력 테이블이 아니라 price_change_log 의 PLATFORM_COMMISSION 행이다.
        ArgumentCaptor<List<PriceChangeLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceChangeLogRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getTargetType()).isEqualTo(PriceTargetType.PLATFORM_COMMISSION);
            assertThat(row.getReason()).isEqualTo(PriceChangeReason.SETTLEMENT_FEEDBACK);
            assertThat(row.getOldPrice()).isEqualByComparingTo("0.05");
            assertThat(row.getNewPrice()).isEqualByComparingTo("0.11");
        });
    }

    @Test
    void applyDoesNotTouchSellingPrice() {
        givenTwoCategories();
        given(platformCategoryRepository.findById(11L)).willReturn(Optional.of(kimchi));

        CommissionApplyResponse response = service.apply(request(11L, "0.106"));

        // ① 판매가는 한 건도 저장되지 않는다.
        verify(listingOptionRepository, never()).save(any());
        // ② 애초에 판매가를 쓸 수 있는 의존성이 없다 — ①을 우회할 방법 자체를 막는다.
        List<String> dependencies = Arrays.stream(CommissionFeedbackServiceImpl.class.getDeclaredFields())
                .map(Field::getType).map(Class::getSimpleName).toList();
        assertThat(dependencies)
                .doesNotContain("ProductListingOptionRepository", "ListingAssetService", "PriceCalculator");
        // ③ 사용자에게 다음 행동을 알려준다(반영은 원가/가격 반영이 소유).
        assertThat(response.notice()).contains("원가/가격 반영");
        assertThat(response.affectedListings()).isEqualTo(1);
    }

    @Test
    void applyRejectsStaleRate() {
        // 화면을 열어둔 사이 실측이 움직였다 — 실측 10.6% 인데 옛 값 20% 로 확정하려 한다.
        givenTwoCategories();
        given(platformCategoryRepository.findById(11L)).willReturn(Optional.of(kimchi));

        assertThatThrownBy(() -> service.apply(request(11L, "0.20")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(platformCategoryRepository, never()).save(any());
    }

    @Test
    void applyRejectsOutOfRange() {
        givenLines(sales(5));

        assertThatThrownBy(() -> service.apply(request(11L, "1.2")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(platformCategoryRepository, never()).save(any());
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private void givenTwoCategories() {
        List<SettlementLine> lines = new ArrayList<>(sales(5));
        lines.addAll(sales(5, sauceCell));
        givenLines(lines);
        givenCategory(kimchiCell, kimchi);
        givenCategory(sauceCell, sauce);
    }

    private CommissionApplyRequest request(Long categoryId, String rate) {
        return new CommissionApplyRequest(null, FROM, TO,
                List.of(new CommissionApplyRequest.Item(categoryId, new BigDecimal(rate))));
    }

    private void givenLines(List<SettlementLine> lines) {
        given(settlementLineRepository.findMatchedForCommissionFeedback(null, FROM, TO)).willReturn(lines);
    }

    private void givenCategory(ProductListing cell, PlatformCategory category) {
        given(masterChannelConfigService.resolvePlatformCategory(cell)).willReturn(category);
    }

    /** 10.6% + 그 부가세 = 실측 11.66% 인 정상 판매 라인들. */
    private List<SettlementLine> sales(int count) {
        return sales(count, kimchiCell);
    }

    private List<SettlementLine> sales(int count, ProductListing cell) {
        List<SettlementLine> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(line(cell, "100000", "10600", "1060"));
        }
        return lines;
    }

    private static SettlementLine line(ProductListing cell, String saleAmount, String fee, String feeVat) {
        ProductListingOption option = ProductListingOption.builder()
                .id(cell.getId() * 10).productListing(cell).optionName("1kg").build();
        return SettlementLine.builder()
                .externalOrderId("O1").platformOptionId("V10").saleType(SaleType.SALE)
                .recognitionDate(LocalDate.of(2026, 8, 10))
                .productListingOption(option)
                .saleAmount(new BigDecimal(saleAmount))
                .serviceFee(new BigDecimal(fee))
                .serviceFeeVat(new BigDecimal(feeVat))
                .build();
    }

    private static CommissionSuggestionView only(List<CommissionSuggestionView> views) {
        assertThat(views).hasSize(1);
        return views.get(0);
    }
}
