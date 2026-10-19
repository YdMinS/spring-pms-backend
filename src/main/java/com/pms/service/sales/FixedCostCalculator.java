package com.pms.service.sales;

import com.pms.domain.FirstMonthState;
import com.pms.domain.FixedCostChargeMode;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.Platform;
import com.pms.domain.PlatformFixedCost;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Channel fixed-cost charge judgement and total (FEATURE_2609_33 / PLAN 2609_33 D2 · D4 · D4-1 · D5 · D11).
 *
 * <p>🔴 <b>This class is the one owner of the total rule.</b> Scattering the judgement over callers makes the channel
 * tab and the seller tab give different answers, and the moment two numbers disagree the whole screen loses trust
 * (same stance as 2609_30's "totals only in the Reconciler").
 *
 * <p><b>Judgement rules</b> (per month × per link):
 * <ol>
 *   <li>Target months = <b>every month the period touches</b> (partial months included, D4)</li>
 *   <li>Months outside {@code appliedFrom}~{@code appliedTo} are skipped (both null = every month, D5)</li>
 *   <li>An inactive catalog item is 0 — past months are not counted either</li>
 *   <li>{@code NEVER} → 0 · {@code ALWAYS} → charged regardless of sales · {@code AUTO} → sales judgement (D2)</li>
 *   <li>AUTO: 🔴 charged when the <b>whole month's</b> sales (after discounts) {@code >=} the effective threshold.
 *       The wording is "1,000,000 won or more", so a month exactly at the threshold is charged</li>
 *   <li>AUTO on an 11st catalog item (FEATURE_2610_20 / D42 · D45 ⑥): charged when the 11st settlement lines of the
 *       <b>previous</b> month (by purchase-confirmed date) sum, sale amount minus refunds, {@code >=} the threshold.
 *       Exception: in M0 ({@code firstMonth}) answered by the person, {@code firstMonthCharged} decides</li>
 * </ol>
 *
 * <p>🔴 <b>No pro-rating</b> (D4). The real charge is a monthly flat amount, so a partial month follows that month's
 * judgement — a 9/1~9/10 period still takes the whole September amount. Telling the user is the screen's job (D4-2).
 *
 * <p>⚠️ {@code from > to} is not checked here — the controller side ({@code Period.of}) already answers 400.
 */
@Component
public class FixedCostCalculator {

    /**
     * Fixed cost this channel bears in the period, without the 11st material — an AUTO link of an 11st catalog item
     * counts 0 here (the sales screen calls the overload below with that material).
     *
     * @param monthlyNetSales key = {@code "YYYY-MM"}, value = 🔴 that <b>whole</b> month's sales after discounts
     *                        (D11). A month without a key is 0 (no sales → under the threshold)
     */
    public FixedCostResult forChannel(List<MarketplaceAccountFixedCost> links,
                                      LocalDate from, LocalDate to,
                                      Map<String, BigDecimal> monthlyNetSales) {
        return forChannel(links, from, to, monthlyNetSales, Map.of());
    }

    /**
     * Same as {@link #forChannel(List, LocalDate, LocalDate, Map)} with the 11st material (FEATURE_2610_20 / D42).
     *
     * @param monthlyConfirmedSales key = {@code "YYYY-MM"} (purchase-confirmed month), value = that month's 11st
     *                              settlement-line sale amount minus refunds. Read only by AUTO links of an 11st catalog
     *                              item; a missing month is 0
     */
    public FixedCostResult forChannel(List<MarketplaceAccountFixedCost> links,
                                      LocalDate from, LocalDate to,
                                      Map<String, BigDecimal> monthlyNetSales,
                                      Map<String, BigDecimal> monthlyConfirmedSales) {
        if (links == null || links.isEmpty() || from == null || to == null) {
            return FixedCostResult.zero();
        }
        Map<String, BigDecimal> sales = monthlyNetSales == null ? Map.of() : monthlyNetSales;
        Map<String, BigDecimal> confirmed = monthlyConfirmedSales == null ? Map.of() : monthlyConfirmedSales;

        BigDecimal total = BigDecimal.ZERO;
        int chargedMonths = 0;
        YearMonth last = YearMonth.from(to);
        for (YearMonth month = YearMonth.from(from); !month.isAfter(last); month = month.plusMonths(1)) {
            String key = month.toString();                       // "2026-09"
            BigDecimal netSales = sales.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal monthTotal = BigDecimal.ZERO;
            for (MarketplaceAccountFixedCost link : links) {
                if (charges(link, key, netSales, confirmed)) {
                    monthTotal = monthTotal.add(link.getPlatformFixedCost().getAmount());
                }
            }
            if (monthTotal.signum() != 0) {
                total = total.add(monthTotal);
                // 🔴 부과된 (달 × 항목) 수가 아니라 "부과가 1건 이상 있었던 달 수"다.
                chargedMonths++;
            }
        }
        return new FixedCostResult(total, chargedMonths);
    }

    /** 연결 1건이 그 달에 부과되는가. */
    private boolean charges(MarketplaceAccountFixedCost link, String month, BigDecimal netSales,
                            Map<String, BigDecimal> confirmed) {
        PlatformFixedCost item = link.getPlatformFixedCost();
        if (item == null || !Boolean.TRUE.equals(item.getActive()) || item.getAmount() == null) {
            return false;
        }
        // 적용 구간 밖의 달 — 계약 전/후까지 빼면 과거 순이익이 실제보다 낮게 나온다(D5).
        if (link.getAppliedFrom() != null && month.compareTo(link.getAppliedFrom()) < 0) {
            return false;
        }
        if (link.getAppliedTo() != null && month.compareTo(link.getAppliedTo()) > 0) {
            return false;
        }
        FixedCostChargeMode mode = link.getChargeMode() == null
                ? FixedCostChargeMode.AUTO : link.getChargeMode();
        return switch (mode) {
            case NEVER -> false;
            case ALWAYS -> true;
            // 🔴 ">=" — the official wording is "1,000,000 won or more" (Coupang) / "5,000,000 won or more" (11st,
            //    FEATURE_2610_20 / D42). ">" would drop a month exactly at the threshold.
            case AUTO -> {
                BigDecimal threshold = link.effectiveThreshold();
                if (threshold == null) {
                    yield false;
                }
                yield item.getPlatform() == Platform.ELEVENST
                        ? elevenstCharges(link, month, threshold, confirmed)
                        : netSales.compareTo(threshold) >= 0;
            }
        };
    }

    /**
     * 11st AUTO (FEATURE_2610_20 / D42 · D45 ⑥): the previous month's 11st purchase-confirmed sales {@code >=}
     * threshold. In M0 answered by the person ({@link FirstMonthState#ANSWERED}) the answer decides instead; in M0
     * while still asking there are no lines of the previous month, so the rule gives 0 won by itself.
     */
    private static boolean elevenstCharges(MarketplaceAccountFixedCost link, String month, BigDecimal threshold,
                                           Map<String, BigDecimal> confirmed) {
        if (month.equals(link.getFirstMonth()) && link.getFirstMonthState() == FirstMonthState.ANSWERED) {
            return Boolean.TRUE.equals(link.getFirstMonthCharged());
        }
        String previous = YearMonth.parse(month).minusMonths(1).toString();
        return confirmed.getOrDefault(previous, BigDecimal.ZERO).compareTo(threshold) >= 0;
    }

    /**
     * 기간 고정비 합계.
     *
     * @param chargedMonths 부과가 1건 이상 있었던 <b>달 수</b>(항목 수가 아니다) — 화면이 판정 근거를
     *                      보여주는 데 쓴다(D11-2)
     */
    public record FixedCostResult(BigDecimal amount, int chargedMonths) {

        public static FixedCostResult zero() {
            return new FixedCostResult(BigDecimal.ZERO, 0);
        }
    }
}
