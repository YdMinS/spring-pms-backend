package com.pms.service.sales;

import com.pms.domain.FirstMonthState;
import com.pms.domain.FixedCostChargeMode;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.PlatformFixedCost;
import com.pms.domain.Platform;
import com.pms.service.sales.FixedCostCalculator.FixedCostResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixed-cost charge judgement (FEATURE_2609_33 / PLAN 2609_33 D2 · D4 · D4-1 · D5 · D11 · FEATURE_2610_20 / D42).
 *
 * <p>🔴 <b>The judgement is the whole of this feature</b> — re-judged every month (D2), a partial month judged on that
 * whole month's sales (D4-1), and the judged amount is <b>after discounts</b> (D11). If one of the three goes silently
 * wrong, net profit drifts by 55,000 per channel per month. An 11st catalog item is judged on the previous month's
 * 11st purchase-confirmed sales instead (D42 — the last two tests).
 *
 * <p>⚠️ The sales here are already "the whole month after discounts" — the SQL building them is not checked through
 * mocks, so {@code OrderLineFixedCostMonthlyAggregationTest} (@DataJpaTest) covers it separately.
 */
class FixedCostCalculatorTest {

    private static final String THRESHOLD = "1000000";
    private static final String AMOUNT = "55000";

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);

    private final FixedCostCalculator calculator = new FixedCostCalculator();

    /** 🔴 경계는 {@code >=} 다 — 공식 문구가 "1백만 원 이상"이라 딱 맞는 달도 부과된다. */
    @Test
    void testAutoChargesWhenSalesReachThreshold() {
        FixedCostResult result = calculator.forChannel(
                List.of(auto()), SEP_1, SEP_30, Map.of("2026-09", new BigDecimal("1000000")));

        assertThat(result.amount()).isEqualByComparingTo("55000");
        assertThat(result.chargedMonths()).isEqualTo(1);
    }

    @Test
    void testAutoSkipsMonthBelowThreshold() {
        FixedCostResult result = calculator.forChannel(
                List.of(auto()), SEP_1, SEP_30, Map.of("2026-09", new BigDecimal("999999")));

        assertThat(result.amount()).isEqualByComparingTo("0");
        assertThat(result.chargedMonths()).isZero();
    }

    /**
     * 🔴 D11 의 방어선. 판정 매출은 {@code grossSales − discount}(할인 후)다 — 호출부가 할인 전 금액을
     * 넘기기 시작하면 쿠폰이 걸린 채널이 <b>부과되지 않은 달을 부과로</b> 판정한다.
     *
     * <p>이 테스트가 없으면 "할인 전으로 판정" 이 조용히 되돌아온다.
     */
    @Test
    void testDiscountLowersJudgementBase() {
        // 할인 전 1,100,000 − 할인 200,000 = 판정 매출 900,000 → 임계 미달
        FixedCostResult result = calculator.forChannel(
                List.of(auto()), SEP_1, SEP_30, Map.of("2026-09", new BigDecimal("900000")));

        assertThat(result.amount()).isEqualByComparingTo("0");
    }

    /** 달마다 다시 따진다 — 8월은 넘고 9월은 미달이면 8월분만이다(D2). */
    @Test
    void testAutoJudgesEachMonth() {
        FixedCostResult result = calculator.forChannel(List.of(auto()), AUG_1, SEP_30,
                Map.of("2026-08", new BigDecimal("2000000"), "2026-09", new BigDecimal("500000")));

        assertThat(result.amount()).isEqualByComparingTo("55000");
        assertThat(result.chargedMonths()).isEqualTo(1);
    }

    /**
     * 🔴 D4-1: 조회 기간이 달을 잘라도 판정 매출은 <b>그 달 전체</b>다. 부분 달 매출로 판정하면
     * 9/1~9/10 조회에서 임계 미달로 보여 "9월엔 고정비가 없다" 가 된다.
     */
    @Test
    void testPartialMonthUsesWholeMonthSales() {
        FixedCostResult result = calculator.forChannel(
                List.of(auto()), SEP_1, SEP_10, Map.of("2026-09", new BigDecimal("2000000")));

        assertThat(result.amount()).isEqualByComparingTo("55000");
    }

    /** {@code ALWAYS} = 실제 청구가 계속 되는 채널. 매출이 0 이어도 매달 부과된다(D2). */
    @Test
    void testAlwaysIgnoresSales() {
        FixedCostResult result = calculator.forChannel(
                List.of(link(FixedCostChargeMode.ALWAYS, null, null, null, true)),
                AUG_1, SEP_30, Map.of());

        assertThat(result.amount()).isEqualByComparingTo("110000");
        assertThat(result.chargedMonths()).isEqualTo(2);
    }

    /** {@code NEVER} = 우리 판정이 임계를 넘겨도 실제로는 청구되지 않는 채널. */
    @Test
    void testNeverIsZero() {
        FixedCostResult result = calculator.forChannel(
                List.of(link(FixedCostChargeMode.NEVER, null, null, null, true)),
                SEP_1, SEP_30, Map.of("2026-09", new BigDecimal("5000000")));

        assertThat(result.amount()).isEqualByComparingTo("0");
    }

    /** 가전·디지털 채널(5,000,000)은 채널 override 가 카탈로그 기본값을 이긴다(D2-1). */
    @Test
    void testThresholdOverrideWins() {
        FixedCostResult result = calculator.forChannel(
                List.of(link(FixedCostChargeMode.AUTO, new BigDecimal("5000000"), null, null, true)),
                SEP_1, SEP_30, Map.of("2026-09", new BigDecimal("2000000")));

        assertThat(result.amount()).isEqualByComparingTo("0");
    }

    /** 계약 시작 전 달은 임계를 넘겨도 빼지 않는다 — 빼면 과거 순이익이 실제보다 낮게 나온다(D5). */
    @Test
    void testAppliedFromClipsEarlierMonths() {
        FixedCostResult result = calculator.forChannel(
                List.of(link(FixedCostChargeMode.AUTO, null, "2026-09", null, true)), AUG_1, SEP_30,
                Map.of("2026-08", new BigDecimal("2000000"), "2026-09", new BigDecimal("2000000")));

        assertThat(result.amount()).isEqualByComparingTo("55000");
        assertThat(result.chargedMonths()).isEqualTo(1);
    }

    /** 종료월 이후 달도 마찬가지다(D5). */
    @Test
    void testAppliedToClipsLaterMonths() {
        FixedCostResult result = calculator.forChannel(
                List.of(link(FixedCostChargeMode.AUTO, null, null, "2026-08", true)), AUG_1, SEP_30,
                Map.of("2026-08", new BigDecimal("2000000"), "2026-09", new BigDecimal("2000000")));

        assertThat(result.amount()).isEqualByComparingTo("55000");
        assertThat(result.chargedMonths()).isEqualTo(1);
    }

    /** 카탈로그에서 끈 항목은 과거 달도 세지 않는다. */
    @Test
    void testInactiveCatalogItemIsZero() {
        FixedCostResult result = calculator.forChannel(
                List.of(link(FixedCostChargeMode.AUTO, null, null, null, false)),
                SEP_1, SEP_30, Map.of("2026-09", new BigDecimal("2000000")));

        assertThat(result.amount()).isEqualByComparingTo("0");
    }

    /** 🔴 {@code chargedMonths} 는 (달 × 항목) 수가 아니라 "부과가 있었던 달 수"다. */
    @Test
    void testTwoItemsSameMonth() {
        MarketplaceAccountFixedCost second = MarketplaceAccountFixedCost.builder()
                .platformFixedCost(item("정기결제이용료", "10000", THRESHOLD, true))
                .chargeMode(FixedCostChargeMode.AUTO)
                .build();

        FixedCostResult result = calculator.forChannel(List.of(auto(), second), SEP_1, SEP_30,
                Map.of("2026-09", new BigDecimal("2000000")));

        assertThat(result.amount()).isEqualByComparingTo("65000");
        assertThat(result.chargedMonths()).isEqualTo(1);
    }

    /** 연결이 없는 채널은 조회를 어떻게 하든 0 이다. */
    @Test
    void testNoLinksIsZero() {
        FixedCostResult result = calculator.forChannel(List.of(), SEP_1, SEP_30,
                Map.of("2026-09", new BigDecimal("9000000")));

        assertThat(result.amount()).isEqualByComparingTo("0");
        assertThat(result.chargedMonths()).isZero();
    }

    /** FEATURE_2610_20 / D42: an 11st link is judged on the previous month's purchase-confirmed sales. */
    @Test
    void elevenstAutoUsesPreviousMonthConfirmedSales() {
        MarketplaceAccountFixedCost elevenst = elevenst(null, null, null);

        FixedCostResult result = calculator.forChannel(List.of(elevenst), SEP_1, SEP_30,
                Map.of("2026-09", new BigDecimal("9000000")),
                Map.of("2026-08", new BigDecimal("5000000"), "2026-09", new BigDecimal("100")));

        assertThat(result.amount()).isEqualByComparingTo("77000");
        assertThat(calculator.forChannel(List.of(elevenst), SEP_1, SEP_30,
                Map.of("2026-09", new BigDecimal("9000000")),
                Map.of("2026-08", new BigDecimal("4999999"))).amount()).isEqualByComparingTo("0");
    }

    /** FEATURE_2610_20 / D45 ⑥: in M0 the person's answer wins over the previous month's lines. */
    @Test
    void elevenstAnsweredFirstMonthFollowsTheAnswer() {
        FixedCostResult charged = calculator.forChannel(
                List.of(elevenst("2026-09", FirstMonthState.ANSWERED, true)), SEP_1, SEP_30,
                Map.of(), Map.of());
        FixedCostResult notCharged = calculator.forChannel(
                List.of(elevenst("2026-09", FirstMonthState.ANSWERED, false)), SEP_1, SEP_30,
                Map.of(), Map.of("2026-08", new BigDecimal("9000000")));
        FixedCostResult asking = calculator.forChannel(
                List.of(elevenst("2026-09", FirstMonthState.ASKING, null)), SEP_1, SEP_30,
                Map.of(), Map.of());

        assertThat(charged.amount()).isEqualByComparingTo("77000");
        assertThat(notCharged.amount()).isEqualByComparingTo("0");
        assertThat(asking.amount()).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------- fixtures

    private MarketplaceAccountFixedCost elevenst(String firstMonth, FirstMonthState state, Boolean charged) {
        return MarketplaceAccountFixedCost.builder()
                .platformFixedCost(PlatformFixedCost.builder()
                        .platform(Platform.ELEVENST).name("11번가 서버이용료")
                        .amount(new BigDecimal("77000")).thresholdAmount(new BigDecimal("5000000"))
                        .active(true).build())
                .chargeMode(FixedCostChargeMode.AUTO)
                .firstMonth(firstMonth)
                .firstMonthState(state)
                .firstMonthCharged(charged)
                .build();
    }

    private MarketplaceAccountFixedCost auto() {
        return link(FixedCostChargeMode.AUTO, null, null, null, true);
    }

    private MarketplaceAccountFixedCost link(FixedCostChargeMode mode, BigDecimal thresholdOverride,
                                             String appliedFrom, String appliedTo, boolean active) {
        return MarketplaceAccountFixedCost.builder()
                .platformFixedCost(item("판매자서비스이용료", AMOUNT, THRESHOLD, active))
                .chargeMode(mode)
                .thresholdOverride(thresholdOverride)
                .appliedFrom(appliedFrom)
                .appliedTo(appliedTo)
                .build();
    }

    private PlatformFixedCost item(String name, String amount, String threshold, boolean active) {
        return PlatformFixedCost.builder()
                .platform(Platform.COUPANG)
                .name(name)
                .amount(new BigDecimal(amount))
                .thresholdAmount(new BigDecimal(threshold))
                .active(active)
                .build();
    }
}
