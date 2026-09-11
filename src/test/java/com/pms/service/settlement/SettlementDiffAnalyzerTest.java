package com.pms.service.settlement;

import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementType;
import com.pms.dto.response.LabelView;
import com.pms.service.MasterChannelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * 차이 리포트 ① — 원인 라벨 분해 (PLAN D12 · D13).
 *
 * <p>🔴 {@code residualGoesToRounding} 이 이 리포트가 거짓말하지 않는다는 유일한 보증이다:
 * 라벨 합 + 잔차 == 총차액 항등식.
 */
@ExtendWith(MockitoExtension.class)
class SettlementDiffAnalyzerTest {

    private static final BigDecimal VAT = new BigDecimal("0.1");

    @Mock private MasterChannelConfigService masterChannelConfigService;

    private SettlementDiffAnalyzer analyzer;

    private final SettlementPayout payout = SettlementPayout.builder()
            .id(1L).settlementType(SettlementType.WEEKLY)
            .settlementDate(LocalDate.of(2026, 9, 4)).build();

    @BeforeEach
    void setUp() {
        analyzer = new SettlementDiffAnalyzer(masterChannelConfigService, VAT);
    }

    @Test
    void feeRateLabelUsesPlatformCategoryCommission() {
        givenCommission("0.106");
        // 실측 11.5% vs 기준 10.6% → 판매금액 × 0.009 만큼 수수료를 더 떼였다.
        // 🔴 부호는 통장 기준이다 — 더 떼였으면 그만큼 <b>덜 받은</b> 것이라 음수다.
        SettlementLine line = saleLine("100000", "11500", "1150", "0.115", null, null, "87350");

        SettlementDiffAnalyzer.DiffReport report = analyzer.analyze(payout, List.of(line));

        assertThat(amountOf(report, SettlementDiffAnalyzer.LABEL_FEE_RATE)).isEqualByComparingTo("-900");
    }

    @Test
    void feeVatLabelIsDifferenceNotTotal() {
        givenCommission("0.106");
        // 실측 VAT(1060) 가 예상(100000 × 0.106 × 0.1 = 1060)과 정확히 같으면 라벨은 0 이다.
        // 실제 VAT 전액을 잡으면 예상 수수료에 이미 든 VAT 와 이중 계상돼 ROUNDING 이 부풀어 오른다.
        SettlementLine line = saleLine("100000", "10600", "1060", "0.106", null, null, "88340");

        SettlementDiffAnalyzer.DiffReport report = analyzer.analyze(payout, List.of(line));

        assertThat(amountOf(report, SettlementDiffAnalyzer.LABEL_FEE_VAT)).isEqualByComparingTo("0");
        assertThat(report.totalDiff()).isEqualByComparingTo("0");
    }

    @Test
    void unknownBaseWhenCategoryUnmapped() {
        willThrow(new IllegalArgumentException("COUPANG 카테고리 매핑 미설정"))
                .given(masterChannelConfigService).resolvePlatformCategory(any());
        SettlementLine line = saleLine("100000", "11500", "1150", "0.115", null, null, "87350");

        SettlementDiffAnalyzer.DiffReport report = analyzer.analyze(payout, List.of(line));

        assertThat(labelNames(report)).contains(SettlementDiffAnalyzer.LABEL_UNKNOWN_BASE)
                .doesNotContain(SettlementDiffAnalyzer.LABEL_FEE_RATE);
        assertThat(report.lines()).singleElement()
                .extracting(view -> view.unmatched()).isEqualTo(false);
    }

    @Test
    void residualGoesToRounding() {
        givenCommission("0.106");
        // 쿠폰·배송비까지 얹고 정산액을 일부러 어긋나게 둔다 — 항등식은 그래도 성립해야 한다.
        SettlementLine line = saleLine("100000", "11500", "1150", "0.115", "3000", "2500", "80000");

        SettlementDiffAnalyzer.DiffReport report = analyzer.analyze(payout, List.of(line));

        BigDecimal labelSum = report.labels().stream()
                .map(LabelView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(labelSum).isEqualByComparingTo(report.totalDiff());
        assertThat(labelNames(report)).contains(SettlementDiffAnalyzer.LABEL_ROUNDING);
    }

    @Test
    void refundLineIsLabelledAndIdentityHolds() {
        SettlementLine refund = saleLine("0", "0", "0", null, null, null, "20000").toBuilder()
                .saleType(SaleType.REFUND).build();

        SettlementDiffAnalyzer.DiffReport report = analyzer.analyze(payout, List.of(refund));

        // 환불은 우리가 돌려준 돈이다 — 통장 기준으로 음수다.
        assertThat(amountOf(report, SettlementDiffAnalyzer.LABEL_REFUND)).isEqualByComparingTo("-20000");
        assertThat(report.totalDiff()).isEqualByComparingTo("-20000");
    }

    @Test
    void unmatchedLinesAreListedButNotEstimated() {
        // 붙일 주문이 없으면 "예상"이 존재하지 않는다 — 목록에는 남기고 라벨 계산에서는 뺀다(D7).
        SettlementLine unmatched = SettlementLine.builder()
                .externalOrderId("O9").platformOptionId("V9").saleType(SaleType.SALE)
                .recognitionDate(LocalDate.of(2026, 9, 1))
                .saleAmount(new BigDecimal("50000")).settlementAmount(new BigDecimal("44000"))
                .build();

        SettlementDiffAnalyzer.DiffReport report = analyzer.analyze(payout, List.of(unmatched));

        assertThat(report.totalDiff()).isEqualByComparingTo("0");
        assertThat(report.lines()).singleElement().satisfies(view -> {
            assertThat(view.unmatched()).isTrue();
            assertThat(view.label()).isEqualTo(SettlementDiffAnalyzer.LABEL_UNMATCHED);
            assertThat(view.settlementDate()).isEqualTo(LocalDate.of(2026, 9, 4));  // 문의용 식별자
        });
    }

    private void givenCommission(String rate) {
        given(masterChannelConfigService.resolvePlatformCategory(any()))
                .willReturn(PlatformCategory.builder().commissionRate(new BigDecimal(rate)).build());
    }

    private static BigDecimal amountOf(SettlementDiffAnalyzer.DiffReport report, String label) {
        return report.labels().stream().filter(view -> view.label().equals(label))
                .map(LabelView::amount).findFirst().orElse(BigDecimal.ZERO);
    }

    private static List<String> labelNames(SettlementDiffAnalyzer.DiffReport report) {
        return report.labels().stream().map(LabelView::label).toList();
    }

    private static SettlementLine saleLine(String saleAmount, String serviceFee, String serviceFeeVat,
                                           String ratio, String coupon, String delivery,
                                           String settlementAmount) {
        ProductListing cell = ProductListing.builder().id(3L).name("행복 김치").build();
        ProductListingOption option = ProductListingOption.builder()
                .id(5L).productListing(cell).optionName("1kg").build();
        return SettlementLine.builder()
                .externalOrderId("O1").platformOptionId("V10").saleType(SaleType.SALE)
                .recognitionDate(LocalDate.of(2026, 9, 1))
                .productListingOption(option)
                .orderLine(OrderLine.builder().id(9L).itemName("행복 김치 1kg")
                        .order(Order.builder().id(2L).build()).build())
                .saleAmount(new BigDecimal(saleAmount))
                .serviceFee(new BigDecimal(serviceFee))
                .serviceFeeVat(new BigDecimal(serviceFeeVat))
                .serviceFeeRatio(ratio == null ? null : new BigDecimal(ratio))
                .couponAmount(coupon == null ? null : new BigDecimal(coupon))
                .deliveryFeeAmount(delivery == null ? null : new BigDecimal(delivery))
                .settlementAmount(new BigDecimal(settlementAmount))
                .build();
    }
}
