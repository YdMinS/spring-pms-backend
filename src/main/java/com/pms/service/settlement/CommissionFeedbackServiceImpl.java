package com.pms.service.settlement;

import com.pms.domain.PlatformCategory;
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
import com.pms.repository.SettlementLineRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.price.PriceHistoryRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link CommissionFeedbackService} 구현 (FEATURE_2609_30 / 06 · PLAN D16).
 *
 * <p><b>필수 규칙 ① 수수료 기준의 출처는 한 곳이다</b> —
 * {@code MasterChannelConfigService.resolvePlatformCategory(cell).getCommissionRate()}. {@code PriceCalculator}
 * (판매가 역산)와 {@code SettlementDiffAnalyzer}(대사 라벨)가 쓰는 바로 그 경로다. 다른 데서 수수료를 꺼내면
 * 판매가 추천·대사·이 제안이 서로 다른 기준으로 말하게 된다.
 *
 * <p><b>필수 규칙 ② 실측은 가중평균이다</b> — {@code Σ(수수료+부가세) ÷ Σ판매금액}. 라인별 비율을 단순
 * 평균하면 1,000원짜리 한 건이 100만원짜리와 같은 무게를 갖는다.
 *
 * <p><b>필수 규칙 ③ 비교 기준을 맞춘다</b> — 실측은 부가세 포함인데 기준표는 부가세 별도라, 그냥 빼면
 * 부가세 10%가 통째로 "수수료율 차이"로 잡혀 모든 카테고리가 제안 목록에 오른다. 기준표 값에
 * {@code × (1 + feeVatRate)} 를 얹어 비교하고, 저장할 때 {@code ÷ (1 + feeVatRate)} 로 되돌린다.
 *
 * <p>🔴 <b>이 클래스는 셀 판매가를 건드리지 않는다.</b> 판매가를 쓸 수 있는 어떤 의존성도 갖지 않는 것이
 * 그 보증이다({@code CommissionFeedbackServiceImplTest.applyDoesNotTouchSellingPrice}). 기존 가격 반영은
 * 원가/가격 반영({@code /api/admin/cost/propagation}) 이 소유한다 — 판매가는 사용자가 트리거할 때만
 * 움직인다(PLAN 2609_28 D4).
 *
 * <p>⚠️ 저장 해상도는 {@code platform_category.commission_rate = DECIMAL(5,2)} 라 <b>1%p</b> 다. 실측
 * 10.6%는 0.11 로 저장된다(기존 카테고리 임포트도 같은 반올림을 겪는다). 응답은 반올림 전 실측을 함께
 * 내려 사용자가 이 차이를 볼 수 있게 한다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class CommissionFeedbackServiceImpl implements CommissionFeedbackService {

    /** 표본이 이보다 적으면 제안이 아니라 소음이다. */
    private static final int DEFAULT_MIN_SAMPLES = 5;

    /** 0.1%p 미만 차이로 사람을 부르지 않는다(반올림 수준). */
    private static final BigDecimal GAP_THRESHOLD = new BigDecimal("0.001");

    /**
     * 낙관적 검증 허용치 0.5%p. 화면을 열어둔 사이 실측이 이보다 더 움직였으면 사용자가 본 값이 아니다.
     * ⚠️ 저장 반올림(최대 0.5%p) 자체는 이 문턱을 넘지 않는다 — 초과일 때만 거절한다.
     */
    private static final BigDecimal STALE_TOLERANCE = new BigDecimal("0.005");

    /** 기간을 안 주면 최근 3개월 — 수수료율은 표본이 쌓여야 의미가 생긴다. */
    private static final int DEFAULT_MONTHS = 3;

    /** 비율 표시 자리수. 저장은 {@link #STORED_SCALE} 로 다시 줄어든다. */
    private static final int RATIO_SCALE = 4;
    private static final int STORED_SCALE = 2;

    private static final String NOTICE =
            "판매가는 아직 그대로입니다 — 영향받는 셀 %d개의 판매가 반영은 [원가/가격 반영]"
                    + "(/api/admin/cost/propagation/preview → apply)에서 실행하세요";

    private final SettlementLineRepository settlementLineRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final MasterChannelConfigService masterChannelConfigService;
    private final PriceHistoryRecorder priceHistoryRecorder;
    private final BigDecimal feeVatRate;

    public CommissionFeedbackServiceImpl(SettlementLineRepository settlementLineRepository,
                                         PlatformCategoryRepository platformCategoryRepository,
                                         MasterChannelConfigService masterChannelConfigService,
                                         PriceHistoryRecorder priceHistoryRecorder,
                                         @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate) {
        this.settlementLineRepository = settlementLineRepository;
        this.platformCategoryRepository = platformCategoryRepository;
        this.masterChannelConfigService = masterChannelConfigService;
        this.priceHistoryRecorder = priceHistoryRecorder;
        this.feeVatRate = feeVatRate == null ? BigDecimal.ZERO : feeVatRate;
    }

    // ── 제안 목록 ────────────────────────────────────────────────────────

    @Override
    public CommissionSuggestionResponse suggestions(Long sellerId, LocalDate from, LocalDate to,
                                                    Integer minSamples) {
        Window window = Window.of(from, to);
        int floor = minSamples(minSamples);

        List<CommissionSuggestionView> suggestions = new ArrayList<>();
        List<CommissionSuggestionView> seedingGaps = new ArrayList<>();

        for (Stat stat : aggregate(sellerId, window).values()) {
            if (stat.samples < floor || stat.saleAmount.signum() <= 0) {
                continue;
            }
            BigDecimal currentRate = stat.category.getCommissionRate();
            if (currentRate == null) {
                // "교정"이 아니라 "빠진 값 채우기"다 — 이 카테고리는 판매가 역산 자체가 400 으로 막힌다.
                seedingGaps.add(view(stat, null, null, null));
                continue;
            }
            BigDecimal currentRatio = withVat(currentRate);
            BigDecimal gap = stat.measuredRatio(feeVatRate).ratio().subtract(currentRatio);
            if (gap.abs().compareTo(GAP_THRESHOLD) < 0) {
                continue;
            }
            suggestions.add(view(stat, currentRate, currentRatio, gap));
        }

        // 금액 영향이 큰 것부터 — 비율 차이만 크고 매출이 없는 카테고리는 뒤로.
        suggestions.sort(Comparator.comparing(CommissionSuggestionView::impact).reversed());
        seedingGaps.sort(Comparator.comparing(CommissionSuggestionView::saleAmount).reversed());
        return new CommissionSuggestionResponse(window.from(), window.to(), floor, feeVatRate,
                suggestions, seedingGaps);
    }

    // ── 확정 반영 ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CommissionApplyResponse apply(CommissionApplyRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("반영할 항목이 없습니다");
        }
        Window window = Window.of(request.from(), request.to());
        // 화면을 열어둔 사이 데이터가 바뀌었는지 보려면 같은 창으로 다시 집계해야 한다.
        Map<Long, Stat> stats = aggregate(request.sellerId(), window);

        int updated = 0;
        int unchanged = 0;
        int affectedListings = 0;
        for (CommissionApplyRequest.Item item : request.items()) {
            BigDecimal requestedRate = validateRate(item.newRate());
            PlatformCategory category = platformCategoryRepository.findById(item.platformCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException("카테고리 없음: " + item.platformCategoryId()));
            Stat stat = requireFreshStat(stats, item, requestedRate);

            BigDecimal newRate = requestedRate.setScale(STORED_SCALE, RoundingMode.HALF_UP);
            BigDecimal oldRate = category.getCommissionRate();
            affectedListings += stat.listings.size();
            if (oldRate != null && oldRate.compareTo(newRate) == 0) {
                unchanged++;
                continue;
            }
            platformCategoryRepository.save(category.toBuilder().commissionRate(newRate).build());
            // 🔴 새 이력 테이블을 만들지 않는다 — price_change_log 의 PLATFORM_COMMISSION 행이다.
            priceHistoryRecorder.recordCommissionRate(category, oldRate, newRate);
            updated++;
        }
        return new CommissionApplyResponse(updated, unchanged, affectedListings,
                String.format(NOTICE, affectedListings));
    }

    /** {@code 0 <= rate < 1} 밖이면 400. 100%를 넘는 수수료는 분모를 음수로 만들어 판매가를 폭주시킨다. */
    private static BigDecimal validateRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("수수료율은 0 이상 1 미만이어야 합니다: " + rate);
        }
        return rate;
    }

    /**
     * 낙관적 검증 — 요청값이 <b>지금</b> 다시 집계한 실측과 0.5%p 넘게 다르면 409.
     *
     * <p>실측이 사라진 경우(구간에 라인이 없다)도 409 다: 사용자가 본 근거가 더 이상 없는데 그대로 저장하면
     * 아무도 설명할 수 없는 수수료율이 남는다.
     */
    private Stat requireFreshStat(Map<Long, Stat> stats, CommissionApplyRequest.Item item, BigDecimal rate) {
        Stat stat = stats.get(item.platformCategoryId());
        if (stat == null || stat.saleAmount.signum() <= 0) {
            throw new BusinessException(
                    "실측 데이터가 없습니다 — 목록을 새로 고쳐 주세요: " + item.platformCategoryId(),
                    HttpStatus.CONFLICT);
        }
        BigDecimal measuredRate = stat.measuredRatio(feeVatRate).rateExclVat();
        if (measuredRate.subtract(rate).abs().compareTo(STALE_TOLERANCE) > 0) {
            throw new BusinessException(
                    "실측 수수료율이 변경되었습니다 — 목록을 새로 고쳐 주세요 (실측 " + measuredRate
                            + ", 요청 " + rate + ")",
                    HttpStatus.CONFLICT);
        }
        return stat;
    }

    // ── 집계 ────────────────────────────────────────────────────────────

    /**
     * 카테고리 단위 가중 집계. 🔴 {@code REFUND} 는 제외한다 — 환불 라인의 수수료는 환급이라 비율의 의미가
     * 반대이고, 섞으면 실측이 실제보다 낮게 나온다.
     *
     * <p>⚠️ 카테고리 매핑이 없는 셀은 조용히 빠진다(예외 아님). 시드 공백 하나 때문에 목록 전체가 500 이
     * 되면 안 된다 — 매핑이 없으면 애초에 제안할 기준표 행이 없다.
     */
    private Map<Long, Stat> aggregate(Long sellerId, Window window) {
        List<SettlementLine> lines = settlementLineRepository
                .findMatchedForCommissionFeedback(sellerId, window.from(), window.to());
        Map<Long, Stat> stats = new LinkedHashMap<>();
        Map<Long, PlatformCategory> cellCache = new HashMap<>();

        for (SettlementLine line : lines) {
            if (line.getSaleType() == SaleType.REFUND) {
                continue;
            }
            ProductListingOption option = line.getProductListingOption();
            ProductListing cell = option == null ? null : option.getProductListing();
            PlatformCategory category = cell == null ? null : category(cell, cellCache);
            if (category == null) {
                continue;
            }
            stats.computeIfAbsent(category.getId(), key -> new Stat(category)).add(line, cell.getId());
        }
        return stats;
    }

    /** 셀 → 매핑된 {@link PlatformCategory}. 매핑이 없으면 null(집계에서 제외). */
    private PlatformCategory category(ProductListing cell, Map<Long, PlatformCategory> cache) {
        if (cache.containsKey(cell.getId())) {
            return cache.get(cell.getId());
        }
        PlatformCategory resolved = null;
        try {
            resolved = masterChannelConfigService.resolvePlatformCategory(cell);
        } catch (RuntimeException e) {
            log.debug("Commission feedback: no category for listing={} ({})", cell.getId(), e.getMessage());
        }
        cache.put(cell.getId(), resolved);
        return resolved;
    }

    private CommissionSuggestionView view(Stat stat, BigDecimal currentRate, BigDecimal currentRatio,
                                          BigDecimal gap) {
        Measured measured = stat.measuredRatio(feeVatRate);
        BigDecimal impact = gap == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : gap.abs().multiply(stat.saleAmount).setScale(2, RoundingMode.HALF_UP);
        PlatformCategory category = stat.category;
        return new CommissionSuggestionView(
                category.getId(),
                category.getCode(),
                category.getName(),
                category.getPlatform(),
                currentRate,
                currentRatio,
                measured.ratio(),
                measured.rateExclVat(),
                measured.rateExclVat().setScale(STORED_SCALE, RoundingMode.HALF_UP),
                gap,
                impact,
                stat.samples,
                stat.listings.size(),
                stat.saleAmount.setScale(2, RoundingMode.HALF_UP),
                stat.first,
                stat.last);
    }

    private BigDecimal withVat(BigDecimal rate) {
        return rate.multiply(BigDecimal.ONE.add(feeVatRate)).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static int minSamples(Integer requested) {
        if (requested == null) {
            return DEFAULT_MIN_SAMPLES;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("최소 표본 수는 1 이상이어야 합니다");
        }
        return requested;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 실측 비율 두 가지 — 부가세 포함(비교용) · 부가세 별도(저장용). */
    private record Measured(BigDecimal ratio, BigDecimal rateExclVat) {
    }

    /** 카테고리 1건의 누적치. 가중평균의 재료는 합계 둘(판매금액·수수료)뿐이다. */
    private static final class Stat {

        private final PlatformCategory category;
        private final Set<Long> listings = new HashSet<>();
        private BigDecimal saleAmount = BigDecimal.ZERO;
        private BigDecimal fee = BigDecimal.ZERO;
        private int samples;
        private LocalDate first;
        private LocalDate last;

        private Stat(PlatformCategory category) {
            this.category = category;
        }

        private void add(SettlementLine line, Long listingId) {
            saleAmount = saleAmount.add(nz(line.getSaleAmount()));
            // 실측은 "쿠팡이 실제로 뗀 것" — 수수료와 그 부가세를 함께 본다.
            fee = fee.add(nz(line.getServiceFee())).add(nz(line.getServiceFeeVat()));
            samples++;
            listings.add(listingId);
            LocalDate date = line.getRecognitionDate();
            if (date != null) {
                first = (first == null || date.isBefore(first)) ? date : first;
                last = (last == null || date.isAfter(last)) ? date : last;
            }
        }

        private Measured measuredRatio(BigDecimal feeVatRate) {
            if (saleAmount.signum() <= 0) {
                BigDecimal zero = BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
                return new Measured(zero, zero);
            }
            BigDecimal ratio = fee.divide(saleAmount, RATIO_SCALE, RoundingMode.HALF_UP);
            BigDecimal exclVat = ratio.divide(BigDecimal.ONE.add(feeVatRate), RATIO_SCALE, RoundingMode.HALF_UP);
            return new Measured(ratio, exclVat);
        }
    }

    /** 조회 구간. 비우면 최근 3개월이고, 뒤집힌 구간은 400 이다. */
    private record Window(LocalDate from, LocalDate to) {

        static Window of(LocalDate from, LocalDate to) {
            LocalDate end = to == null ? LocalDate.now() : to;
            LocalDate start = from == null ? end.minusMonths(DEFAULT_MONTHS) : from;
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다");
            }
            return new Window(start, end);
        }
    }
}
