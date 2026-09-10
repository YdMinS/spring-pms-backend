package com.pms.service.sales;

import com.pms.domain.FixedCostChargeMode;
import com.pms.domain.MarketplaceAccountFixedCost;
import com.pms.domain.PlatformFixedCost;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 채널 고정비 부과 판정·합계 (FEATURE_2609_33 / PLAN 2609_33 D2 · D4 · D4-1 · D5 · D11).
 *
 * <p>🔴 <b>합계 규칙의 소유자는 이 클래스 하나</b>다. 판정을 호출부에 흩뿌리면 채널 탭과 판매자 탭이
 * 서로 다른 답을 내고, 두 숫자가 어긋나는 순간 화면 전체가 신뢰를 잃는다
 * (2609_30 의 "합계는 Reconciler 만" 과 같은 자세).
 *
 * <p><b>판정 규칙</b>(달마다 × 연결마다):
 * <ol>
 *   <li>대상 달 = 조회 기간이 <b>걸친 달 전부</b>(부분 달 포함, D4)</li>
 *   <li>{@code appliedFrom}~{@code appliedTo} 밖의 달은 제외(둘 다 null 이면 전부, D5)</li>
 *   <li>카탈로그 항목이 비활성이면 0 — 과거 달도 세지 않는다</li>
 *   <li>{@code NEVER} → 0 · {@code ALWAYS} → 매출 무관 부과 · {@code AUTO} → 매출 판정(D2)</li>
 *   <li>AUTO: 🔴 <b>그 달 전체</b> 매출(할인 후) {@code >=} 실효 임계면 부과. "1백만 원 이상"이라
 *       기준 금액과 같으면 부과된다</li>
 * </ol>
 *
 * <p>🔴 <b>일할 계산하지 않는다</b>(D4). 실제 부과가 월 1회 정액이라 부분 달도 그 달 판정을 그대로
 * 따른다 — 9/1~9/10 조회에서도 9월 전액이 빠진다. 그 사실을 사용자에게 알리는 것은 화면 몫이다(D4-2).
 *
 * <p>⚠️ {@code from > to} 는 여기서 검증하지 않는다 — 컨트롤러 상위({@code Period.of})가 이미 400 을 낸다.
 */
@Component
public class FixedCostCalculator {

    /**
     * 조회 기간에 이 채널이 부담하는 고정비.
     *
     * @param monthlyNetSales key = {@code "YYYY-MM"}, 값 = 🔴 그 달 <b>전체</b>의 할인 후 매출(D11).
     *                        키가 없는 달은 0(판매가 없었으면 임계 미달)
     */
    public FixedCostResult forChannel(List<MarketplaceAccountFixedCost> links,
                                      LocalDate from, LocalDate to,
                                      Map<String, BigDecimal> monthlyNetSales) {
        if (links == null || links.isEmpty() || from == null || to == null) {
            return FixedCostResult.zero();
        }
        Map<String, BigDecimal> sales = monthlyNetSales == null ? Map.of() : monthlyNetSales;

        BigDecimal total = BigDecimal.ZERO;
        int chargedMonths = 0;
        YearMonth last = YearMonth.from(to);
        for (YearMonth month = YearMonth.from(from); !month.isAfter(last); month = month.plusMonths(1)) {
            String key = month.toString();                       // "2026-09"
            BigDecimal netSales = sales.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal monthTotal = BigDecimal.ZERO;
            for (MarketplaceAccountFixedCost link : links) {
                if (charges(link, key, netSales)) {
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
    private boolean charges(MarketplaceAccountFixedCost link, String month, BigDecimal netSales) {
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
            // 🔴 ">=" — 공식 문구가 "1백만 원 이상"이다. ">" 로 쓰면 딱 맞는 달이 빠진다.
            case AUTO -> {
                BigDecimal threshold = link.effectiveThreshold();
                yield threshold != null && netSales.compareTo(threshold) >= 0;
            }
        };
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
