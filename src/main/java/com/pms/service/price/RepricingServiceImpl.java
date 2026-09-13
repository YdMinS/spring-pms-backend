package com.pms.service.price;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.dto.response.RepricingCandidatesResponse;
import com.pms.dto.response.RepricingCandidatesResponse.Exclusion;
import com.pms.dto.response.RepricingCandidatesResponse.Group;
import com.pms.dto.response.RepricingCandidatesResponse.Row;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.PriceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 마진 경보 조회 구현(FEATURE_2609_39 / 01). 저장·전송 없음.
 *
 * <p><b>쿼리 예산</b>(D16) — 옵션 수가 아니라 <b>셀 수</b>에 비례해야 한다:</p>
 * <ol>
 *   <li>대상 셀 1쿼리({@code findRepricingTargets} — SELLING·쿠팡·판매자 필터가 전부 쿼리 안에 있다)</li>
 *   <li>옵션 1쿼리 + 동반 로드 1쿼리({@code findWithConfigByIdIn} 의 {@code @EntityGraph})</li>
 *   <li>BOM 1쿼리({@code findWithProductByOptionIdIn} — product 까지 fetch)</li>
 *   <li>수수료·마진 프리셋 해석은 <b>셀당 1회</b>, 택배·박스 해석은 (셀, 마스터옵션)당 1회 — 캐시</li>
 * </ol>
 *
 * <p>🔴 한 행이 죽어도 요청 전체가 죽으면 안 된다: 수수료 미설정 셀 하나 때문에 판매자 전체 목록이 400 이 되면
 * 정작 고쳐야 할 나머지를 볼 수 없다. 계산 실패는 그 행만 {@code UNCALCULABLE} 로 내려보낸다.</p>
 *
 * <p>⚠️ 테넌트 스코프는 <b>셀 쿼리에서 시작</b>한다(D24) — {@code ProductListingOption} 에는 {@code @TenantId}
 * 가 없어 id 기반 조회가 테넌트 필터를 타지 않는다. 옵션 id 는 반드시 이 테넌트의 셀에서 얻은 것만 쓴다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepricingServiceImpl implements RepricingService {

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final PriceCalculator priceCalculator;

    @Override
    public RepricingCandidatesResponse candidates(Long sellerId, Platform platform, Scope scope) {
        Scope effectiveScope = scope == null ? Scope.BELOW : scope;

        List<ProductListing> cells = productListingRepository.findRepricingTargets(sellerId, platform);
        if (cells.isEmpty()) {
            return new RepricingCandidatesResponse(List.of(), List.of());
        }

        List<ProductListingOption> options = loadOptions(cells);
        Map<Long, BigDecimal> costSums = costSums(options);

        // Per-cell and per-(cell, master option) resolution caches — the whole point of D16.
        Map<Long, CellBasis> cellBasisCache = new HashMap<>();
        Map<String, OptionBasis> optionBasisCache = new HashMap<>();

        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (ProductListingOption option : options) {
            ProductListing cell = option.getProductListing();
            CellBasis cellBasis = cellBasisCache.computeIfAbsent(cell.getId(), id -> resolveCellBasis(cell));
            Row row = row(cell, option, cellBasis, costSums.getOrDefault(option.getId(), BigDecimal.ZERO),
                    optionBasisCache);
            rows.add(row);
            accumulators
                    .computeIfAbsent(groupKey(cell), key -> new Accumulator(cell.getSeller().getId(),
                            cell.getSeller().getSellerName(), cell.getPlatform().name()))
                    .add(row, cellBasis.basis());
        }

        List<Group> groups = accumulators.values().stream()
                .map(Accumulator::toGroup)
                .sorted(Comparator.comparing(Group::sellerName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Group::platform))
                .toList();

        // scope 는 행만 거른다 — 「대응 필요인데 실행할 수 없는 행」을 숨기면 D23 이 무의미해지므로 below 가
        // 기준이고 excluded 는 기준이 아니다.
        List<Row> visible = effectiveScope == Scope.ALL ? rows : rows.stream().filter(Row::below).toList();
        return new RepricingCandidatesResponse(groups, visible);
    }

    // ---------------------------------------------------------------- loading

    /**
     * 대상 옵션 로드. 제외 규칙 중 <b>마켓 식별자 없음</b>을 여기서 거른다(셀 상태·플랫폼은 쿼리가 이미 걸렀다).
     * 동반 로드는 기존 {@code findWithConfigByIdIn} 을 그대로 쓴다 — 셀·마스터·마스터옵션이 한 쿼리에 딸려온다.
     */
    private List<ProductListingOption> loadOptions(List<ProductListing> cells) {
        List<Long> cellIds = cells.stream().map(ProductListing::getId).toList();
        List<Long> optionIds = productListingOptionRepository.findByProductListingIdIn(cellIds).stream()
                .filter(option -> option.getPlatformOptionId() != null)
                .map(ProductListingOption::getId)
                .toList();
        if (optionIds.isEmpty()) {
            return List.of();
        }
        return productListingOptionRepository.findWithConfigByIdIn(optionIds).stream()
                .sorted(Comparator.comparing(ProductListingOption::getId))
                .toList();
    }

    /** Σ(product.price × quantity) per option, from ONE BOM query (null price = 0). */
    private Map<Long, BigDecimal> costSums(List<ProductListingOption> options) {
        if (options.isEmpty()) {
            return Map.of();
        }
        Set<Long> optionIds = options.stream().map(ProductListingOption::getId).collect(Collectors.toSet());
        Map<Long, BigDecimal> sums = new HashMap<>();
        for (ProductListingProduct line : productListingProductRepository.findWithProductByOptionIdIn(optionIds)) {
            BigDecimal price = line.getProduct().getPrice();
            if (price == null) {
                continue;
            }
            sums.merge(line.getProductListingOption().getId(),
                    price.multiply(BigDecimal.valueOf(line.getQuantity())), BigDecimal::add);
        }
        return sums;
    }

    // ---------------------------------------------------------------- per row

    private Row row(ProductListing cell, ProductListingOption option, CellBasis cellBasis,
                    BigDecimal costSum, Map<String, OptionBasis> optionBasisCache) {
        BigDecimal marketPrice = option.getMarketPrice();
        BigDecimal sellingPrice = option.getSellingPrice();
        BigDecimal judgedPrice = marketPrice != null ? marketPrice : sellingPrice;
        // D15: NULL 은 「아직 안 밀림」이 아니라 「알 수 없음」이다. NULL 을 안 밀린 것으로 세면 새로 등록·편입한
        // 상품이 전부 여기 쌓인다.
        boolean pendingPush = marketPrice != null && sellingPrice != null
                && marketPrice.compareTo(sellingPrice) != 0;
        boolean manual = option.getPriceSource() == GeneratedContentSource.MANUAL_OVERRIDE;

        if (cellBasis.error() != null) {
            return uncalculable(cell, option, judgedPrice, marketPrice, sellingPrice, pendingPush,
                    cellBasis.error());
        }
        OptionBasis optionBasis = optionBasisCache.computeIfAbsent(
                cell.getId() + "|" + masterOptionId(option),
                key -> resolveOptionBasis(cellBasis.basis(), cell, option.getMasterProductOption()));
        if (optionBasis.error() != null) {
            return uncalculable(cell, option, judgedPrice, marketPrice, sellingPrice, pendingPush,
                    optionBasis.error());
        }

        PriceCalculator.CostBreakdown breakdown;
        BigDecimal newPrice;
        try {
            breakdown = priceCalculator.breakdown(optionBasis.basis(), costSum, judgedPrice);
            newPrice = priceCalculator.prices(optionBasis.basis(), costSum).salePrice();
        } catch (RuntimeException e) {
            return uncalculable(cell, option, judgedPrice, marketPrice, sellingPrice, pendingPush,
                    e.getMessage());
        }

        boolean below = below(cellBasis.basis(), breakdown);
        return new Row(cell.getId(), cell.getName(), option.getId(), option.getOptionName(),
                cell.getSeller().getId(), cell.getPlatform().name(),
                judgedPrice, breakdown.costSum(), breakdown.delivery(), breakdown.box(),
                breakdown.feeAmount(), breakdown.marginAmount(), breakdown.marginRate(),
                newPrice, marketPrice, sellingPrice, pendingPush, below,
                manual ? Exclusion.MANUAL : null,
                manual ? "직접 지정한 가격" : null);
    }

    /**
     * 대응 필요 판정(D4): 기준값이 <b>하나라도</b> 밑돌면 true. NULL 기준 = 그 조건 미사용이므로 두 기준이 다
     * 비어 있으면 마진이 음수여도 경보하지 않는다(지어낸 기본선으로 전 상품을 대응 필요로 만들지 않는다).
     */
    private static boolean below(PriceCalculator.CellPricingBasis basis, PriceCalculator.CostBreakdown breakdown) {
        BigDecimal minAmount = basis.minMarginAmount();
        BigDecimal minRate = basis.minMarginRate();
        return (minAmount != null && breakdown.marginAmount().compareTo(minAmount) < 0)
                || (minRate != null && breakdown.marginRate().compareTo(minRate) < 0);
    }

    /**
     * 계산 불가 행. 🔴 {@code below = false} — 마진을 못 냈는데 「기준 미달」이라고 말할 수는 없다. 직접 지정가
     * 이면서 계산까지 불가한 행은 더 강한 쪽인 {@code UNCALCULABLE} 로 표시한다(어차피 실행 대상이 아니다).
     */
    private static Row uncalculable(ProductListing cell, ProductListingOption option, BigDecimal judgedPrice,
                                    BigDecimal marketPrice, BigDecimal sellingPrice, boolean pendingPush,
                                    String reason) {
        return new Row(cell.getId(), cell.getName(), option.getId(), option.getOptionName(),
                cell.getSeller().getId(), cell.getPlatform().name(),
                judgedPrice, null, null, null, null, null, null,
                null, marketPrice, sellingPrice, pendingPush, false,
                Exclusion.UNCALCULABLE, reason);
    }

    // ---------------------------------------------------------------- basis resolution (cached)

    private CellBasis resolveCellBasis(ProductListing cell) {
        try {
            return new CellBasis(priceCalculator.resolveCellBasis(cell), null);
        } catch (RuntimeException e) {
            log.debug("Repricing: cell {} has no pricing basis ({})", cell.getId(), e.getMessage());
            return new CellBasis(null, e.getMessage());
        }
    }

    private OptionBasis resolveOptionBasis(PriceCalculator.CellPricingBasis cellBasis, ProductListing cell,
                                           MasterProductOption masterOption) {
        try {
            return new OptionBasis(priceCalculator.resolveBasis(cellBasis, cell, masterOption), null);
        } catch (RuntimeException e) {
            log.debug("Repricing: cell {} has no delivery/box basis ({})", cell.getId(), e.getMessage());
            return new OptionBasis(null, e.getMessage());
        }
    }

    /** ⚠️ id 만 읽는다 — LAZY 프록시라 여기서 초기화하면 옵션마다 쿼리가 하나씩 더 나간다. */
    private static Long masterOptionId(ProductListingOption option) {
        MasterProductOption masterOption = option.getMasterProductOption();
        return masterOption == null ? null : masterOption.getId();
    }

    private static String groupKey(ProductListing cell) {
        return cell.getSeller().getId() + "|" + cell.getPlatform().name();
    }

    /** 해석 결과 또는 그 실패 사유. 실패도 캐시한다 — 같은 셀에서 같은 예외를 옵션마다 다시 던지지 않는다. */
    private record CellBasis(PriceCalculator.CellPricingBasis basis, String error) {
    }

    private record OptionBasis(PriceCalculator.PricingBasis basis, String error) {
    }

    /** 판매자 × 채널 집계기. {@code scope} 와 무관하게 <b>대상 전부</b>를 센다(D18). */
    private static final class Accumulator {
        private final Long sellerId;
        private final String sellerName;
        private final String platform;
        private int optionCount;
        private int belowCount;
        private int belowManualCount;
        private int pendingPushCount;
        private BigDecimal targetMarginRate;

        private Accumulator(Long sellerId, String sellerName, String platform) {
            this.sellerId = sellerId;
            this.sellerName = sellerName;
            this.platform = platform;
        }

        private void add(Row row, PriceCalculator.CellPricingBasis basis) {
            optionCount++;
            if (row.below()) {
                if (row.excluded() == Exclusion.MANUAL) {
                    belowManualCount++;
                } else {
                    belowCount++;
                }
            }
            if (row.pendingPush()) {
                pendingPushCount++;
            }
            if (targetMarginRate == null && basis != null) {
                targetMarginRate = basis.targetMarginRate();
            }
        }

        private Group toGroup() {
            return new Group(sellerId, sellerName, platform, optionCount, belowCount, belowManualCount,
                    pendingPushCount, targetMarginRate);
        }
    }
}
