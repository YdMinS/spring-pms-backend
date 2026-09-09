package com.pms.service.settlement;

import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.dto.response.LabelView;
import com.pms.dto.response.ReconLineView;
import com.pms.service.MasterChannelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 차이 리포트 ① — <b>추정 vs 실정산</b>을 라인 단위로 원인 라벨로 분해한다
 * (FEATURE_2609_30 / PLAN D12 상단 · D13).
 *
 * <p><b>필수 규칙</b>: 추정 수수료의 출처는 {@code PriceCalculator} 와 <b>같은 한 곳</b>이다 —
 * {@code MasterChannelConfigService.resolvePlatformCategory(cell).getCommissionRate()}. 다른 데서 수수료를
 * 꺼내면 판매가 추천과 대사가 서로 다른 기준으로 말하게 된다.
 *
 * <p>🔴 <b>항등식이 이 클래스의 계약이다</b>: {@code Σ 라벨 금액 == 총차액}. 마지막 라벨 {@code ROUNDING} 은
 * 나머지 전부를 흡수하므로 설명하지 못한 금액이 사라지지 않는다. 라벨을 추가할 때도 이 항등식을 깨지 말 것 —
 * 깨지는 순간 리포트가 거짓말을 시작한다.
 *
 * <p>🔴 <b>라벨은 응답 필드에서만 판정한다</b>(D13). 추정으로 사유를 지어내지 않는다. 셀에 매핑된 카테고리가
 * 없어 수수료 기준이 없으면 {@code FEE_RATE} 가 아니라 {@code UNKNOWN_BASE} 다(금액 0 · 건수로 드러낸다).
 *
 * <p>⚠️ 대상은 <b>매칭된 라인</b>뿐이다. 미분류(UNMATCHED)는 붙일 주문이 없어 추정 자체가 불가능하므로
 * 리포트 ②(묶음 단위)에서 건수로만 보여준다.
 */
@Slf4j
@Component
public class SettlementDiffAnalyzer {

    public static final String LABEL_FEE_RATE = "FEE_RATE";
    public static final String LABEL_FEE_VAT = "FEE_VAT";
    public static final String LABEL_SELLER_COUPON = "SELLER_COUPON";
    public static final String LABEL_DELIVERY = "DELIVERY";
    public static final String LABEL_REFUND = "REFUND";
    public static final String LABEL_UNKNOWN_BASE = "UNKNOWN_BASE";
    /** 위 항목으로 설명되지 않은 나머지. 항상 마지막이며 항등식을 성립시키는 잔차다. */
    public static final String LABEL_ROUNDING = "ROUNDING";
    /** 설명할 차이가 없는 라인의 표시값(집계 버킷이 아니다). */
    public static final String LABEL_NONE = "NONE";
    /** 매칭된 주문이 없어 추정 자체가 불가능한 라인. */
    public static final String LABEL_UNMATCHED = "UNMATCHED";

    /** 라벨로 설명되지 않은 잔차가 총차액의 이 비율을 넘으면 리포트에 경고 문구를 단다. */
    private static final BigDecimal RESIDUAL_WARN_RATIO = new BigDecimal("0.20");

    private static final String[] LABEL_ORDER = {
            LABEL_FEE_RATE, LABEL_FEE_VAT, LABEL_SELLER_COUPON, LABEL_DELIVERY,
            LABEL_REFUND, LABEL_UNKNOWN_BASE, LABEL_ROUNDING};

    private final MasterChannelConfigService masterChannelConfigService;
    private final BigDecimal feeVatRate;

    public SettlementDiffAnalyzer(MasterChannelConfigService masterChannelConfigService,
                                  @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate) {
        this.masterChannelConfigService = masterChannelConfigService;
        this.feeVatRate = feeVatRate == null ? BigDecimal.ZERO : feeVatRate;
    }

    /**
     * 묶음의 라인들을 원인 라벨로 분해한다.
     *
     * @param payout 라인 목록에 실을 지급일·정산유형의 출처(문의용 식별자)
     * @param lines  묶음에 귀속된 라인 전부. 미분류도 넣는다 — 라벨 계산에서만 빠지고 목록에는 남는다
     */
    public DiffReport analyze(SettlementPayout payout, List<SettlementLine> lines) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<Long, BigDecimal> commissionCache = new HashMap<>();
        List<ReconLineView> views = new ArrayList<>();

        BigDecimal expectedTotal = BigDecimal.ZERO;
        BigDecimal actualTotal = BigDecimal.ZERO;

        for (SettlementLine line : lines) {
            boolean unmatched = line.getOrderLine() == null;
            if (unmatched) {
                // 붙일 주문이 없으면 "예상"이 존재하지 않는다 — 지어내지 않고 ②(묶음 단위)로 넘긴다.
                views.add(view(payout, line, LABEL_UNMATCHED, null, true));
                continue;
            }

            LineDiff diff = analyzeLine(line, commissionCache);
            expectedTotal = expectedTotal.add(diff.expected());
            actualTotal = actualTotal.add(diff.actual());
            diff.labels().forEach((label, amount) -> {
                totals.merge(label, amount, BigDecimal::add);
                if (amount.signum() != 0 || LABEL_UNKNOWN_BASE.equals(label)) {
                    counts.merge(label, 1, Integer::sum);
                }
            });
            views.add(view(payout, line, diff.primaryLabel(), diff.diff(), false));
        }

        BigDecimal totalDiff = scale(expectedTotal.subtract(actualTotal));
        return new DiffReport(scale(expectedTotal), scale(actualTotal), totalDiff,
                labelViews(totals, counts, totalDiff), views);
    }

    /**
     * 라인 1건 분해.
     *
     * <pre>
     *   expectedFee = saleAmount × commissionRate × (1 + feeVatRate)     ← 우리 추정(수수료 부가세 포함)
     *   actualFee   = serviceFee + serviceFeeVat                         ← 실제
     *   diff        = (saleAmount − expectedFee) − settlementAmount      ← 예상보다 덜 받은 금액
     * </pre>
     *
     * <p>🔴 {@code FEE_RATE} 는 <b>둘 다 VAT 제외 기준</b>으로 뺀다(쿠팡 문서: serviceFeeRatio 는 VAT 제외).
     * 🔴 {@code FEE_VAT} 는 <b>VAT 의 "차이"만</b> 잡는다 — 예상 수수료에 이미 예상 VAT 가 들어 있어(위 식의
     * {@code × (1 + feeVatRate)}) 실제 VAT 전액을 라벨로 잡으면 이중 계상되고 그만큼 ROUNDING 이 부풀어
     * 경고가 상시 발동한다.
     */
    private LineDiff analyzeLine(SettlementLine line, Map<Long, BigDecimal> commissionCache) {
        Map<String, BigDecimal> labels = new LinkedHashMap<>();
        BigDecimal settlementAmount = nz(line.getSettlementAmount());

        if (line.getSaleType() == SaleType.REFUND) {
            // 환불은 "예상 0, 실제 −금액" 이다. 전액이 곧 차이이고 다른 라벨을 붙이지 않는다.
            labels.put(LABEL_REFUND, scale(settlementAmount));
            return new LineDiff(BigDecimal.ZERO, settlementAmount.negate(), scale(settlementAmount),
                    labels, LABEL_REFUND);
        }

        BigDecimal saleAmount = nz(line.getSaleAmount());
        BigDecimal actualFee = nz(line.getServiceFee()).add(nz(line.getServiceFeeVat()));
        BigDecimal commissionRate = commissionRate(line, commissionCache);

        BigDecimal expectedFee;
        if (commissionRate == null) {
            // 기준이 없으면 차이를 주장하지 않는다 — 실제를 그대로 예상으로 두고 건수로만 드러낸다(D13).
            expectedFee = actualFee;
            labels.put(LABEL_UNKNOWN_BASE, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        } else {
            expectedFee = saleAmount.multiply(commissionRate).multiply(BigDecimal.ONE.add(feeVatRate));
            BigDecimal ratio = line.getServiceFeeRatio();
            BigDecimal feeRateDiff = ratio != null
                    ? saleAmount.multiply(ratio.subtract(commissionRate))
                    : nz(line.getServiceFee()).subtract(saleAmount.multiply(commissionRate));
            labels.put(LABEL_FEE_RATE, scale(feeRateDiff));
            labels.put(LABEL_FEE_VAT, scale(nz(line.getServiceFeeVat())
                    .subtract(saleAmount.multiply(commissionRate).multiply(feeVatRate))));
        }

        BigDecimal coupon = nz(line.getCouponAmount());
        if (coupon.signum() > 0) {
            labels.put(LABEL_SELLER_COUPON, scale(coupon));
        }
        BigDecimal delivery = nz(line.getDeliveryFeeAmount());
        if (delivery.signum() != 0) {
            labels.put(LABEL_DELIVERY, scale(delivery));
        }

        BigDecimal expected = saleAmount.subtract(expectedFee);
        BigDecimal diff = scale(expected.subtract(settlementAmount));

        // 잔차 = 위 라벨로 설명되지 않은 나머지. 이것이 항등식을 성립시킨다.
        BigDecimal explained = labels.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        labels.put(LABEL_ROUNDING, scale(diff.subtract(explained)));

        return new LineDiff(scale(expected), scale(settlementAmount), diff, labels, primaryLabel(labels, diff));
    }

    /** 표시용 대표 라벨 = 절대금액이 가장 큰 원인. 차이가 없는 라인은 {@code NONE}. */
    private static String primaryLabel(Map<String, BigDecimal> labels, BigDecimal diff) {
        if (diff.abs().compareTo(BigDecimal.ONE) < 0) {
            return LABEL_NONE;
        }
        return labels.entrySet().stream()
                .max((left, right) -> left.getValue().abs().compareTo(right.getValue().abs()))
                .map(Map.Entry::getKey)
                .orElse(LABEL_ROUNDING);
    }

    /**
     * 셀에 매핑된 {@link PlatformCategory} 의 수수료율. 매핑이 없으면 null 이다(→ {@code UNKNOWN_BASE}).
     *
     * <p>⚠️ 매핑 실패는 예외가 아니다 — 리포트 하나가 카테고리 시드 공백 때문에 통째로 500 이 되면 안 된다.
     */
    private BigDecimal commissionRate(SettlementLine line, Map<Long, BigDecimal> cache) {
        ProductListingOption option = line.getProductListingOption();
        ProductListing cell = option == null ? null : option.getProductListing();
        if (cell == null) {
            return null;
        }
        if (cache.containsKey(cell.getId())) {
            return cache.get(cell.getId());
        }
        BigDecimal rate = null;
        try {
            rate = masterChannelConfigService.resolvePlatformCategory(cell).getCommissionRate();
        } catch (RuntimeException e) {
            log.debug("Settlement report: no commission base for listing={} ({})", cell.getId(), e.getMessage());
        }
        cache.put(cell.getId(), rate);
        return rate;
    }

    private List<LabelView> labelViews(Map<String, BigDecimal> totals, Map<String, Integer> counts,
                                       BigDecimal totalDiff) {
        List<LabelView> views = new ArrayList<>();
        for (String label : LABEL_ORDER) {
            BigDecimal amount = totals.get(label);
            int count = counts.getOrDefault(label, 0);
            if (amount == null || (amount.signum() == 0 && count == 0)) {
                continue;
            }
            views.add(new LabelView(label, scale(amount), count, detail(label, amount, count, totalDiff)));
        }
        return views;
    }

    /**
     * 라벨 설명. 🔴 잔차가 총차액의 20% 를 넘으면 <b>작은 라벨에 숨기지 않고</b> 그렇다고 말한다 —
     * 설명 못 하는 금액을 그럴듯한 이름에 감추면 리포트 전체가 거짓말이 된다.
     */
    private static String detail(String label, BigDecimal amount, int count, BigDecimal totalDiff) {
        if (LABEL_UNKNOWN_BASE.equals(label)) {
            return "카테고리 매핑이 없어 수수료를 추정할 수 없는 라인 " + count + "건 — 차액을 판정하지 않았습니다";
        }
        if (LABEL_ROUNDING.equals(label)
                && totalDiff.abs().signum() > 0
                && amount.abs().compareTo(totalDiff.abs().multiply(RESIDUAL_WARN_RATIO)) > 0) {
            return "설명되지 않은 금액이 총차액의 20%를 넘습니다 — 라벨로 분해되지 않은 차이입니다";
        }
        return null;
    }

    private ReconLineView view(SettlementPayout payout, SettlementLine line, String label,
                               BigDecimal diff, boolean unmatched) {
        return new ReconLineView(
                line.getExternalOrderId(),
                line.getPlatformOptionId(),
                productName(line),
                line.getRecognitionDate(),
                payout.getSettlementDate(),
                payout.getSettlementType() == null ? null : payout.getSettlementType().name(),
                line.getSaleAmount(),
                line.getServiceFee(),
                line.getServiceFeeRatio(),
                line.getSettlementAmount(),
                diff,
                label,
                unmatched);
    }

    /** 문의용 상품명 — 주문 라인의 이름이 우선(플랫폼이 부른 그대로), 없으면 셀 + 옵션명. */
    private static String productName(SettlementLine line) {
        if (line.getOrderLine() != null && line.getOrderLine().getItemName() != null) {
            return line.getOrderLine().getItemName();
        }
        ProductListingOption option = line.getProductListingOption();
        if (option == null) {
            return null;
        }
        ProductListing cell = option.getProductListing();
        return cell == null ? option.getOptionName() : cell.getName() + " / " + option.getOptionName();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 라인 1건의 분해 결과(내부). */
    private record LineDiff(BigDecimal expected, BigDecimal actual, BigDecimal diff,
                            Map<String, BigDecimal> labels, String primaryLabel) {
    }

    /**
     * 리포트 ① 결과.
     *
     * @param totalDiff {@code expected − actual}. 🔴 {@code Σ labels.amount == totalDiff} 가 항상 성립한다
     */
    public record DiffReport(BigDecimal expected, BigDecimal actual, BigDecimal totalDiff,
                             List<LabelView> labels, List<ReconLineView> lines) {
    }
}
