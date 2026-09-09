package com.pms.service;

import com.pms.domain.CoupangOrderLine;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.ShoppingListItem;
import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.response.PurchaseLine;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.dto.response.PurchaseProductGroup;
import com.pms.dto.response.PurchaseRecordView;
import com.pms.dto.response.UnmappedOrder;
import com.pms.config.CoupangProperties;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.ShoppingListItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * {@link PurchaseListService} 구현. 추출/조회/구매기록/수동추가/조정.
 *
 * 클래스 기본 readOnly, 쓰기 메서드만 @Transactional 오버라이드.
 * Entity 는 @Setter 금지 — 갱신은 toBuilder 로 새 객체 생성.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PurchaseListServiceImpl implements PurchaseListService {

    /** 사입 대상 = 결제완료. 중립 상태로 판정한다(FEATURE_2609_26 / PLAN D4). */
    private static final OrderStatus PURCHASE_TARGET_STATUS = OrderStatus.PAID;

    private final ShoppingListItemRepository shoppingListItemRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final OrderLineRepository orderLineRepository;
    private final CoupangOrderLineRepository coupangOrderLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final ProductRepository productRepository;
    private final CoupangProperties coupangProperties;

    @Override
    @Transactional
    public void extract(Long sellerId) {
        // 1) 주문 연결 라인 autoQty 전체 리셋 → 출고/취소된 주문 라인은 아래 재적재에서 제외돼 자연히 빠짐.
        //    ⚠️ 수동 추가 라인(order_line_id IS NULL)은 건드리지 않는다.
        shoppingListItemRepository.resetAllAutoQty();

        // 2) 결제완료 주문을 옵션→BOM 전개해 (order_line, product) 라인 upsert.
        List<OrderLine> lines = purchaseTargetLines(sellerId);
        Map<Long, String> vendorItemIds = vendorItemIdsByLine(lines);
        for (OrderLine line : lines) {
            int q = line.purchasableQty();
            if (q <= 0) continue;

            String vendorItemId = vendorItemIds.get(line.getId());
            if (vendorItemId == null) continue;  // 거울 행 없음 = 옵션 매칭 키가 없다

            Optional<ProductListingOption> optionOpt =
                    productListingOptionRepository.findByPlatformOptionId(vendorItemId);
            if (optionOpt.isEmpty()) continue;   // 미매핑 → 조회에서 unmapped 로 노출

            List<ProductListingProduct> boms =
                    productListingProductRepository.findByProductListingOptionId(optionOpt.get().getId());
            for (ProductListingProduct bom : boms) {
                int lineQty = q * bom.getQuantity();
                ShoppingListItem item = shoppingListItemRepository
                        .findByOrderLine_IdAndProduct_Id(line.getId(), bom.getProduct().getId())
                        .map(existing -> existing.toBuilder().autoQty(lineQty).build())  // auto 만 교체, manual 보존
                        .orElseGet(() -> ShoppingListItem.builder()
                                .orderLine(line)
                                .product(bom.getProduct())
                                .autoQty(lineQty)
                                .manualQty(0)
                                .build());
                shoppingListItemRepository.save(item);
            }
        }
    }

    @Override
    public PurchaseListResponse getList(Long sellerId) {
        // 아직 사야 할 것: 잔여 > 0.
        List<PurchaseProductGroup> groups = buildGroups(i -> true, g -> g.remainingQty() > 0);
        return new PurchaseListResponse(groups, buildUnmapped(sellerId));
    }

    @Override
    public List<PurchaseProductGroup> getCompletedList(Long sellerId, LocalDate from, LocalDate to) {
        // 판매자 필터: 주문 라인의 판매자가 일치하는 라인만(수동 라인은 판매자 없으므로 제외).
        Predicate<ShoppingListItem> itemFilter = sellerId == null
                ? i -> true
                : i -> i.getOrderLine() != null
                        && i.getOrderLine().getOrder().getMarketplaceAccount() != null
                        && sellerId.equals(i.getOrderLine().getOrder().getMarketplaceAccount().getSeller().getId());

        // 구매 완료: 잔여 <= 0 이면서 실제 구매가 있었던 것만. (필요=0 & 구매=0 유령 라인 제외.)
        // 기간 필터: 그룹 안에 구매일이 [from, to] 에 드는 구매 기록이 하나라도 있으면 포함.
        Predicate<PurchaseProductGroup> keep = g -> g.remainingQty() <= 0
                && g.purchasedQty() > 0
                && hasRecordInRange(g, from, to);

        return buildGroups(itemFilter, keep);
    }

    /** 그룹의 구매 기록 중 구매일이 [from, to](경계 포함)에 드는 것이 있는지. 둘 다 null 이면 항상 통과. */
    private boolean hasRecordInRange(PurchaseProductGroup g, LocalDate from, LocalDate to) {
        if (from == null && to == null) return true;
        return g.lines().stream()
                .flatMap(l -> l.records().stream())
                .anyMatch(r -> (from == null || !r.purchasedOn().isBefore(from))
                        && (to == null || !r.purchasedOn().isAfter(to)));
    }

    /**
     * shopping_list_item 중 {@code itemFilter} 통과분을 product 로 합산해 그룹을 만들고
     * {@code keep} 을 통과한 것만 반환.
     * 필요수량 = Σ(autoQty+manualQty), 구매수량 = Σ(purchase_record.quantity), 잔여 = 필요 − 구매.
     */
    private List<PurchaseProductGroup> buildGroups(
            Predicate<ShoppingListItem> itemFilter,
            Predicate<PurchaseProductGroup> keep) {
        List<ShoppingListItem> items = shoppingListItemRepository.findAll().stream()
                .filter(itemFilter)
                .toList();

        // itemId 별 구매수량 합 (records 도 함께 보유).
        List<Long> itemIds = items.stream().map(ShoppingListItem::getId).toList();
        Map<Long, List<PurchaseRecord>> recordsByItem = itemIds.isEmpty()
                ? Map.of()
                : purchaseRecordRepository.findByItem_IdIn(itemIds).stream()
                        .collect(Collectors.groupingBy(r -> r.getItem().getId()));

        // product 단위 그룹화 (입력 순서 보존).
        Map<Long, List<ShoppingListItem>> byProduct = items.stream()
                .collect(Collectors.groupingBy(i -> i.getProduct().getId(), LinkedHashMap::new, Collectors.toList()));

        List<PurchaseProductGroup> groups = new ArrayList<>();
        for (List<ShoppingListItem> lineItems : byProduct.values()) {
            int needed = lineItems.stream().mapToInt(ShoppingListItem::neededQty).sum();

            int purchased = 0;
            List<PurchaseLine> lines = new ArrayList<>();
            for (ShoppingListItem li : lineItems) {
                List<PurchaseRecord> recs = recordsByItem.getOrDefault(li.getId(), List.of());
                int linePurchased = recs.stream().mapToInt(PurchaseRecord::getQuantity).sum();
                purchased += linePurchased;
                lines.add(toLine(li, linePurchased, recs));
            }

            Product p = lineItems.get(0).getProduct();
            PurchaseProductGroup group = new PurchaseProductGroup(
                    p.getId(), p.getProductName(), needed, purchased, needed - purchased, lines);
            if (keep.test(group)) {
                groups.add(group);
            }
        }
        return groups;
    }

    @Override
    @Transactional
    public void addPurchase(Long itemId, PurchaseRecordRequest request) {
        ShoppingListItem item = shoppingListItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("ShoppingListItem", itemId));
        // 금액 계산 규칙은 엔티티 팩토리 하나에 모여 있다(FEATURE_2609_28 / PLAN D1·D2).
        // ⚠️ reflectToBasePrice 는 저장만 한다 — Product.price 파급은 별도 기능이 소유(D4).
        purchaseRecordRepository.save(PurchaseRecord.of(item, request));
    }

    @Override
    @Transactional
    public void addManual(ManualItemRequest request) {
        ShoppingListItem item = shoppingListItemRepository
                .findByOrderLineIsNullAndProduct_Id(request.productId())
                .map(existing -> existing.toBuilder()
                        .manualQty(existing.getManualQty() + request.quantity())   // 누적
                        .build())
                .orElseGet(() -> {
                    Product product = productRepository.findById(request.productId())
                            .orElseThrow(() -> new ResourceNotFoundException("Product", request.productId()));
                    return ShoppingListItem.builder()
                            .orderLine(null)
                            .product(product)
                            .autoQty(0)
                            .manualQty(request.quantity())
                            .build();
                });
        shoppingListItemRepository.save(item);
    }

    @Override
    @Transactional
    public void adjustManual(Long itemId, ManualAdjustRequest request) {
        ShoppingListItem item = shoppingListItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("ShoppingListItem", itemId));
        shoppingListItemRepository.save(item.toBuilder()
                .manualQty(request.manualQty())   // 절대값 교체
                .build());
    }

    // --- helpers ---

    private List<OrderLine> purchaseTargetLines(Long sellerId) {
        // 동기화 윈도우(syncDays) 밖 주문은 상태가 갱신되지 않아 stale 결제완료로 남을 수 있으므로,
        // 구매목록 추출도 같은 윈도우(orders.ordered_at 기준)로 제한한다.
        LocalDateTime from = LocalDate.now().minusDays(coupangProperties.getSyncDays()).atStartOfDay();
        return sellerId == null
                ? orderLineRepository.findRecentByStatus(PURCHASE_TARGET_STATUS, from)
                : orderLineRepository.findRecentByStatusAndSeller(PURCHASE_TARGET_STATUS, sellerId, from);
    }

    /**
     * 라인 id → 옵션 매칭키(vendorItemId). 거울 행을 라인마다 다시 읽지 않고 한 번에 가져온다
     * (FEATURE_2609_26 / 04 §3-3).
     */
    private Map<Long, String> vendorItemIdsByLine(List<OrderLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = lines.stream().map(OrderLine::getId).toList();
        return coupangOrderLineRepository.findByOrderLine_IdIn(ids).stream()
                .collect(Collectors.toMap(m -> m.getOrderLine().getId(), CoupangOrderLine::getVendorItemId,
                        (a, b) -> a));
    }

    private PurchaseLine toLine(ShoppingListItem li, int linePurchased, List<PurchaseRecord> recs) {
        OrderLine line = li.getOrderLine();
        List<PurchaseRecordView> recordViews = recs.stream()
                .map(r -> new PurchaseRecordView(r.getId(), r.getPurchasedOn(), r.getQuantity(),
                        r.getTotalAmount(), r.getUnitPrice(), Boolean.TRUE.equals(r.getReflectToBasePrice())))
                .toList();
        return new PurchaseLine(
                li.getId(),
                line != null ? line.getId() : null,
                line != null ? "ORDER" : "MANUAL",
                line != null ? line.getOrder().getExternalOrderId() : null,
                li.getAutoQty(),
                li.getManualQty(),
                linePurchased,
                recordViews);
    }

    /** 결제완료인데 옵션 미매핑이거나 BOM 이 빈 주문을 vendorItemId 단위로 집계. */
    private List<UnmappedOrder> buildUnmapped(Long sellerId) {
        List<OrderLine> lines = purchaseTargetLines(sellerId);
        Map<Long, String> vendorItemIds = vendorItemIdsByLine(lines);
        Map<String, List<OrderLine>> byItem = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            if (line.purchasableQty() <= 0) continue;
            String vendorItemId = vendorItemIds.get(line.getId());
            if (vendorItemId == null) continue;
            Optional<ProductListingOption> optionOpt =
                    productListingOptionRepository.findByPlatformOptionId(vendorItemId);
            boolean mapped = optionOpt.isPresent()
                    && !productListingProductRepository.findByProductListingOptionId(optionOpt.get().getId()).isEmpty();
            if (!mapped) {
                byItem.computeIfAbsent(vendorItemId, k -> new ArrayList<>()).add(line);
            }
        }
        return byItem.entrySet().stream()
                .map(e -> {
                    List<OrderLine> group = e.getValue();
                    int qty = group.stream().mapToInt(OrderLine::purchasableQty).sum();
                    return new UnmappedOrder(e.getKey(), group.get(0).getItemName(), qty, group.size());
                })
                .toList();
    }
}
