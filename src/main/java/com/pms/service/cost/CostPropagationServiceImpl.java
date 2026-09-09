package com.pms.service.cost;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.PurchaseRecord;
import com.pms.dto.response.CostDeviation;
import com.pms.dto.response.PropagateResponse;
import com.pms.dto.response.PropagationApplyResult;
import com.pms.dto.response.PropagationPreview;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.service.listing.MasterPropagationService;
import com.pms.service.price.PriceHistoryRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Collectors;

/**
 * 원가 파급 구현 (FEATURE_2609_28 / PLAN D4). 계약·경계는 {@link CostPropagationService} 참조.
 *
 * <p>⚠️ <b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> {@link #apply} 는 마스터마다
 * {@code MasterPropagationService.propagate} 를 부르고 그 안에서 셀마다 {@code REQUIRES_NEW} 가 도는데,
 * 부모 트랜잭션이 열려 있으면 셀 하나의 실패가 부모를 rollback-only 로 만든다(기존 계약).
 * 읽기·쓰기 경계가 필요한 메서드에만 개별로 붙인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostPropagationServiceImpl implements CostPropagationService {

    /** 파급 대상 기본 조회 창 — "최근에 매입가가 들어온 물품". 쿼리 파라미터로 덮어쓸 수 있다. */
    private static final int DEFAULT_PREVIEW_DAYS = 7;

    /**
     * 괴리 목록 조회 창. 파라미터로 열지 않는다 — 화면이 필요로 하는 것은 "요즘 산 것 대비 기준가가
     * 낡았는가"이고, 창을 무한정 열면 몇 년 치 매입을 전부 훑는 쿼리가 된다.
     */
    private static final int DEVIATION_LOOKBACK_DAYS = 90;

    /** 괴리 목록 기본/최대 건수. 사람이 훑어보는 목록이라 상한을 둔다. */
    private static final int DEFAULT_DEVIATION_LIMIT = 20;
    private static final int MAX_DEVIATION_LIMIT = 100;

    /** {@code products.price} 는 DECIMAL(38,2) — 단가(scale 4)를 넣기 전에 <b>우리가</b> 반올림한다. */
    private static final int BASE_PRICE_SCALE = 2;

    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ProductRepository productRepository;
    private final MasterProductOptionItemRepository masterProductOptionItemRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final MasterPropagationService masterPropagationService;
    private final PriceHistoryRecorder priceHistoryRecorder;

    // ---------------------------------------------------------------- ① 기준가 갱신

    @Override
    @Transactional
    public void updateBasePrice(PurchaseRecord record) {
        // 조건은 엔티티가 소유한다(PLAN D3). 여기서 다시 풀어 쓰면 preview 의 대상 산출과 갈라진다.
        if (!record.movesBasePrice()) {
            return;
        }
        Product product = record.getProduct();
        // The purchase price becomes the new base cost — nothing else is touched here (PLAN 2609_28 D4 ①).
        // ⚠️ 파급(②)을 부르지 않는다. 여기서 부르면 구매기록 저장이 파급 실패에 끌려 롤백된다.
        BigDecimal oldPrice = product.getPrice();
        BigDecimal newPrice = record.getUnitPrice().setScale(BASE_PRICE_SCALE, RoundingMode.HALF_UP);
        productRepository.save(product.toBuilder().price(newPrice).build());
        // 🔴 원가 변경 이력 훅 ① (D23). 이전 값과 매입기록 id 를 둘 다 아는 유일한 자리다 —
        //    여기서 안 남기면 "왜 기준가가 올랐나"는 나중에 어디서도 답이 나오지 않는다.
        //    프로모션 매입(reflect_to_base_price=false)은 위 early return 에서 이미 빠졌으므로
        //    기준가를 안 건드리고 이력도 안 남는다.
        priceHistoryRecorder.recordProductCost(product, oldPrice, newPrice,
                PriceChangeReason.PURCHASE_UPDATE, record.getId());
    }

    // ---------------------------------------------------------------- ② dry-run

    @Override
    @Transactional(readOnly = true)
    public PropagationPreview preview(LocalDate since) {
        LocalDate from = (since == null) ? LocalDate.now().minusDays(DEFAULT_PREVIEW_DAYS) : since;

        // 🔴 세 조건을 SQL 에 복제하지 않는다 — 창만 DB 가 좁히고 판정은 Step 1 과 같은 메서드가 한다.
        List<Long> productIds = purchaseRecordRepository.findPurchasedOnOrAfter(from).stream()
                .filter(PurchaseRecord::movesBasePrice)
                .map(r -> r.getProduct().getId())
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return new PropagationPreview(List.of(), 0, 0, List.of());
        }

        // 물품 → 마스터 옵션 → 마스터. 이름은 join fetch 로 함께 받는다(셀의 LAZY 마스터를 깨우지 않는다).
        Map<Long, String> masterNames = new LinkedHashMap<>();
        for (MasterProductOptionItem item : masterProductOptionItemRepository
                .findWithMasterByProductIdIn(productIds)) {
            masterNames.putIfAbsent(item.getOption().getMasterProduct().getId(),
                    item.getOption().getMasterProduct().getName());
        }
        if (masterNames.isEmpty()) {
            return new PropagationPreview(List.of(), 0, 0, List.of());
        }

        List<ProductListing> cells = productListingRepository
                .findByMasterProductIdIn(new ArrayList<>(masterNames.keySet()));
        List<Long> cellIds = cells.stream().map(ProductListing::getId).toList();
        Map<Long, List<ProductListingOption>> optionsByCell = cellIds.isEmpty()
                ? Map.of()
                : productListingOptionRepository.findByProductListingIdIn(cellIds).stream()
                        .collect(Collectors.groupingBy(o -> o.getProductListing().getId()));
        Set<Long> cellsWithAssets = cellIds.isEmpty()
                ? Set.of()
                : generatedProductDataRepository.findByProductListingIdIn(cellIds).stream()
                        .map(g -> g.getProductListing().getId())
                        .collect(Collectors.toCollection(HashSet::new));

        Map<Long, int[]> countsByMaster = new HashMap<>();   // masterId -> {cellCount, optionCount}
        List<PropagationPreview.SkippedCell> skipped = new ArrayList<>();
        for (ProductListing cell : cells) {
            // ⚠️ id 만 읽는다 — LAZY 프록시라도 추가 쿼리가 나가지 않는다.
            Long masterId = cell.getMasterProduct().getId();
            PropagationPreview.SkipReason reason = skipReason(cell, optionsByCell, cellsWithAssets);
            if (reason != null) {
                skipped.add(new PropagationPreview.SkippedCell(cell.getId(), masterId, cell.getName(), reason));
                continue;
            }
            int autoOptions = (int) optionsByCell.getOrDefault(cell.getId(), List.of()).stream()
                    .filter(CostPropagationServiceImpl::isAutoPriced)
                    .count();
            int[] counts = countsByMaster.computeIfAbsent(masterId, k -> new int[2]);
            counts[0]++;
            counts[1] += autoOptions;
        }

        List<PropagationPreview.AffectedMaster> masters = countsByMaster.entrySet().stream()
                .map(e -> new PropagationPreview.AffectedMaster(
                        e.getKey(), masterNames.get(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparing(PropagationPreview.AffectedMaster::masterId))
                .toList();
        int totalCells = masters.stream().mapToInt(PropagationPreview.AffectedMaster::cellCount).sum();
        return new PropagationPreview(masters, masters.size(), totalCells, skipped);
    }

    /** 제외 사유. null = 파급 대상. 순서가 규칙이다 — DRAFT 셀은 마켓 id 도 없으므로 먼저 본다. */
    private PropagationPreview.SkipReason skipReason(ProductListing cell,
                                                     Map<Long, List<ProductListingOption>> optionsByCell,
                                                     Set<Long> cellsWithAssets) {
        if (cell.getStatus() == ListingStatus.DRAFT) {
            return PropagationPreview.SkipReason.DRAFT;
        }
        if (cell.getPlatformProductId() == null) {
            return PropagationPreview.SkipReason.NOT_APPROVED;
        }
        if (!cellsWithAssets.contains(cell.getId())) {
            // propagate 가 실제로 건너뛰는 조건(자산 없는 셀 = 재생성이 아니라 최초 생성 대상).
            return PropagationPreview.SkipReason.NO_ASSETS;
        }
        List<ProductListingOption> options = optionsByCell.getOrDefault(cell.getId(), List.of());
        if (options.stream().noneMatch(CostPropagationServiceImpl::isAutoPriced)) {
            return PropagationPreview.SkipReason.MANUAL;
        }
        return null;
    }

    /** 수동 지정가는 재생성이 건드리지 않는다(2609_19 규칙) — 미리보기도 세지 않는다. */
    private static boolean isAutoPriced(ProductListingOption option) {
        return option.getPriceSource() != GeneratedContentSource.MANUAL_OVERRIDE;
    }

    // ---------------------------------------------------------------- ② 확정 실행

    @Override
    public PropagationApplyResult apply(List<Long> masterIds) {
        int propagated = 0;
        int skipped = 0;
        int failedCells = 0;
        int erroredMasters = 0;
        List<PropagationApplyResult.MasterResult> results = new ArrayList<>();

        for (Long masterId : masterIds) {
            try {
                // ♻️ 기존 파급 엔진을 그대로 쓴다 — BOM 동기화 + 옵션 원가·판매가 재계산 + needsMarketSync.
                //    🔴 여기서 채널 push 를 부르지 않는다. 그것이 ③ 이고 사람이 실행한다.
                PropagateResponse response = masterPropagationService.propagate(masterId);
                propagated += response.getPropagated();
                skipped += response.getSkipped();
                failedCells += response.getFailed();
                results.add(new PropagationApplyResult.MasterResult(masterId,
                        response.getPropagated(), response.getSkipped(), response.getFailed(), null));
            } catch (Exception e) {
                // 마스터 하나가 실패해도 나머지는 계속한다 — 이미 커밋된 셀을 되돌리지 않는다.
                erroredMasters++;
                log.warn("[COST-PROPAGATE] masterId={} propagate failed: {}", masterId, e.getMessage());
                results.add(new PropagationApplyResult.MasterResult(masterId, 0, 0, 0, e.getMessage()));
            }
        }

        String status;
        if (!masterIds.isEmpty() && erroredMasters == masterIds.size()) {
            status = "FAILED";
        } else if (erroredMasters > 0 || failedCells > 0) {
            status = "PARTIAL";
        } else {
            status = "SUCCESS";
        }
        return new PropagationApplyResult(status, masterIds.size(), propagated, skipped, failedCells, results);
    }

    // ---------------------------------------------------------------- 괴리 목록

    @Override
    @Transactional(readOnly = true)
    public List<CostDeviation> deviations(int limit) {
        int capped = Math.min(limit <= 0 ? DEFAULT_DEVIATION_LIMIT : limit, MAX_DEVIATION_LIMIT);
        LocalDate from = LocalDate.now().minusDays(DEVIATION_LOOKBACK_DAYS);

        // 최신순으로 오므로 물품마다 첫 행이 최근 매입이다. 판정은 여기서도 movesBasePrice 하나가 한다.
        Map<Long, PurchaseRecord> latestByProduct = new LinkedHashMap<>();
        for (PurchaseRecord record : purchaseRecordRepository.findPurchasedOnOrAfter(from)) {
            if (record.movesBasePrice()) {
                latestByProduct.putIfAbsent(record.getProduct().getId(), record);
            }
        }

        List<CostDeviation> deviations = new ArrayList<>();
        for (PurchaseRecord record : latestByProduct.values()) {
            Product product = record.getProduct();
            BigDecimal basePrice = product.getPrice();
            if (basePrice == null || basePrice.signum() == 0) {
                // 기준가가 없거나 0 이면 괴리율이 정의되지 않는다. 지어내지 않고 목록에서 뺀다.
                continue;
            }
            BigDecimal latest = record.getUnitPrice();
            BigDecimal diffRate = latest.subtract(basePrice)
                    .divide(basePrice, 4, RoundingMode.HALF_UP);
            deviations.add(new CostDeviation(product.getId(), product.getProductName(),
                    basePrice, latest, diffRate, record.getPurchasedOn()));
        }
        deviations.sort(Comparator.comparing((CostDeviation d) -> d.diffRate().abs()).reversed());
        return deviations.size() > capped ? List.copyOf(deviations.subList(0, capped)) : deviations;
    }
}
