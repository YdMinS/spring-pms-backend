package com.pms.service;

import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Product;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.PurchaseRecord;
import com.pms.domain.Seller;
import com.pms.domain.ShoppingListItem;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.PurchaseLine;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.dto.response.PurchaseProductGroup;
import com.pms.dto.response.PurchaseRecordResult;
import com.pms.dto.response.PurchaseRecordView;
import com.pms.dto.response.UnmappedOrder;
import com.pms.config.CoupangProperties;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.service.cost.CostPropagationService;
import com.pms.service.stock.StockLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * {@link PurchaseListService} 구현. 추출/조회/입고/수동추가/조정.
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
    private final SellerRepository sellerRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final StockLedgerService stockLedgerService;
    private final CostPropagationService costPropagationService;
    private final CoupangProperties coupangProperties;

    @Override
    @Transactional
    public void extract() {
        // 1) 주문 연결 라인 autoQty 전체 리셋 → 출고/취소된 주문 라인은 아래 재적재에서 제외돼 자연히 빠짐.
        //    ⚠️ 수동 추가 라인(order_line_id IS NULL)은 건드리지 않는다.
        //    리셋 범위(전체)와 재적재 범위(전체)가 일치한다 — 판매자 스코프가 없어졌기 때문이다(PLAN 2609_29 D11).
        shoppingListItemRepository.resetAllAutoQty();

        // 2) 결제완료 주문을 옵션→BOM 전개해 (order_line, product) 라인 upsert.
        List<OrderLine> lines = purchaseTargetLines();
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
    public PurchaseListResponse getList() {
        // 아직 사야 할 것: 잔여 > 0.
        List<PurchaseProductGroup> groups = buildGroups((group, records) -> group.remainingQty() > 0);
        return new PurchaseListResponse(groups, buildUnmapped());
    }

    @Override
    public List<PurchaseProductGroup> getCompletedList(LocalDate from, LocalDate to) {
        // 구매 완료: 잔여 <= 0 이면서 실제 구매가 있었던 것만. (필요=0 & 구매=0 유령 라인 제외.)
        // 기간 필터: 그 물품의 구매 기록 중 구매일이 [from, to] 에 드는 것이 하나라도 있으면 포함.
        // 🔴 판정 조건은 그대로다(PLAN 2609_29 D21) — 완료 = 구매가 필요를 채웠다.
        //    판정 재료만 "라인에 묶인 기록"에서 "물품의 기록"으로 바뀌었다(D3 이 라인 FK 를 없앴다).
        return buildGroups((group, records) -> group.remainingQty() <= 0
                && group.purchasedQty() > 0
                && hasRecordInRange(records, from, to));
    }

    /** 구매 기록 중 구매일이 [from, to](경계 포함)에 드는 것이 있는지. 둘 다 null 이면 항상 통과. */
    private boolean hasRecordInRange(List<PurchaseRecord> records, LocalDate from, LocalDate to) {
        if (from == null && to == null) return true;
        return records.stream()
                .anyMatch(r -> (from == null || !r.getPurchasedOn().isBefore(from))
                        && (to == null || !r.getPurchasedOn().isAfter(to)));
    }

    /**
     * shopping_list_item 전체를 product 로 합산해 그룹을 만들고 {@code keep} 을 통과한 것만 반환.
     *
     * <p>필요수량 = Σ(autoQty+manualQty), 구매수량 = Σ(그 <b>물품</b>의 purchase_record.quantity),
     * 잔여 = 필요 − 구매. 🔴 세 숫자 모두 전체 기준이다 — 그룹 키는 물품이고 판매자로 쪼개지 않는다
     * (PLAN 2609_29 D6). 구매기록은 주문을 모르므로(D3) 라인별 구매수량은 존재할 수 없다(D7).
     *
     * <p>{@code keep} 은 그룹과 <b>그 물품의 구매기록</b>을 함께 받는다 — 완료탭의 기간 판정이 라인이 아니라
     * 물품 기록으로 서기 때문이다.
     *
     * <p>⚠️ N+1 금지: 물품명·채널 라벨은 루프 밖에서 id 묶음으로 한 번에 읽어 Map 으로 붙인다.
     */
    private List<PurchaseProductGroup> buildGroups(
            BiPredicate<PurchaseProductGroup, List<PurchaseRecord>> keep) {
        List<ShoppingListItem> items = shoppingListItemRepository.findAll();
        if (items.isEmpty()) {
            return List.of();
        }

        List<Long> productIds = items.stream().map(i -> i.getProduct().getId()).distinct().toList();
        Map<Long, List<PurchaseRecord>> recordsByProduct = purchaseRecordRepository
                .findByProduct_IdIn(productIds).stream()
                .collect(Collectors.groupingBy(r -> r.getProduct().getId()));
        Map<Long, String> productNames = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getProductName));
        Map<Long, ChannelLabel> channels = channelLabels(items);

        // product 단위 그룹화 (입력 순서 보존).
        Map<Long, List<ShoppingListItem>> byProduct = items.stream()
                .collect(Collectors.groupingBy(i -> i.getProduct().getId(), LinkedHashMap::new, Collectors.toList()));

        List<PurchaseProductGroup> groups = new ArrayList<>();
        for (Map.Entry<Long, List<ShoppingListItem>> entry : byProduct.entrySet()) {
            Long productId = entry.getKey();
            List<ShoppingListItem> lineItems = entry.getValue();

            int needed = lineItems.stream().mapToInt(ShoppingListItem::neededQty).sum();
            List<PurchaseRecord> records = recordsByProduct.getOrDefault(productId, List.of());
            int purchased = records.stream().mapToInt(PurchaseRecord::getQuantity).sum();

            List<PurchaseLine> lines = lineItems.stream().map(li -> toLine(li, channels)).toList();

            PurchaseProductGroup group = new PurchaseProductGroup(
                    productId, productNames.get(productId), needed, purchased, needed - purchased, lines);
            if (keep.test(group, records)) {
                groups.add(group);
            }
        }
        return groups;
    }

    @Override
    @Transactional
    public PurchaseRecordResult addPurchase(PurchaseRecordRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.productId()));
        Seller seller = sellerRepository.findById(request.sellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", request.sellerId()));

        // 금액 계산 규칙은 엔티티 팩토리 하나에 모여 있다(FEATURE_2609_28 / PLAN D1·D2).
        PurchaseRecord saved = purchaseRecordRepository.save(PurchaseRecord.of(product, seller, request));

        // ① 기준가 갱신(2609_28 D4). reflectToBasePrice·단가·수량 조건은 서비스가 소유한다.
        // ⚠️ 여기서 파급(②=셀 판매가 재계산)은 일어나지 않는다 — 사용자가 preview → apply 로 확정한다.
        costPropagationService.updateBasePrice(saved);

        // D19: 기본은 즉시 반영. false 면 구매기록만 남고 입고대기로 간다(화면은 아직 스위치를 못 끈다).
        // D17: 음수 정정은 "금액을 잘못 적었다"이지 "물건이 나갔다"가 아니다 — STOCK_IN 이 거부한다.
        boolean wantStock = (request.recordStock() == null) || request.recordStock();
        boolean stockRecorded = wantStock && request.quantity() > 0;
        if (stockRecorded) {
            // ⚠️ record 는 @Transactional(REQUIRED) 이라 이 트랜잭션에 합류한다 → 재고 실패 시 구매기록도 롤백.
            //    REQUIRES_NEW 로 바꾸면 "돈만 남고 물건은 없는" 행이 조용히 생긴다.
            // ⚠️ 단가를 넘기지 않는다(D16): 원장이 구매기록에서 승계한다. 여기서 계산하면 규칙이 두 벌이 된다.
            stockLedgerService.record(new StockMovementRequest(
                    request.productId(),          // productId
                    request.sellerId(),           // sellerId
                    StockMovementType.STOCK_IN,   // movementType
                    request.quantity(),           // quantity
                    StockReason.PURCHASE,         // reason
                    null,                         // reasonNote
                    null,                         // unitPrice — 구매기록에서 승계
                    null,                         // orderClaimId
                    saved.getId(),                // purchaseRecordId
                    request.purchasedOn()));      // movedOn
        }
        return new PurchaseRecordResult(saved.getId(), stockRecorded);
    }

    @Override
    public List<PurchaseRecordView> recentPurchases(Long productId, int limit) {
        return purchaseRecordRepository.findRecentByProduct(productId, PageRequest.of(0, limit)).stream()
                .map(r -> new PurchaseRecordView(r.getId(), r.getPurchasedOn(), r.getQuantity(),
                        r.getTotalAmount(), r.getUnitPrice(), Boolean.TRUE.equals(r.getReflectToBasePrice()),
                        r.getSeller().getSellerName()))
                .toList();
    }

    @Override
    @Transactional
    public void addManual(ManualItemRequest request) {
        // ⚠️ 판매자가 없다(PLAN 2609_29 D13): 수동 추가는 "이 물품이 N개 더 필요"라는 수요이지 매입이 아니다.
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

    /** 라인 토글의 채널 칩 라벨 = 판매자 × 플랫폼 ("A상사/쿠팡"). 수동 라인은 이 값이 없다. */
    private record ChannelLabel(Long marketplaceAccountId, String sellerName, String platform) {}

    /**
     * 계정 id → 채널 라벨. 계정 수만큼만 조회한다 — 라인마다 LAZY 프록시의 getSellerName() 을 부르면
     * 라인 수만큼 쿼리가 나간다(PLAN 2609_29 D8 ⑤).
     */
    private Map<Long, ChannelLabel> channelLabels(List<ShoppingListItem> items) {
        List<Long> accountIds = items.stream()
                .map(ShoppingListItem::getOrderLine)
                .filter(Objects::nonNull)
                // Order.marketplaceAccount 는 nullable = false 라 주문 라인이면 항상 있다.
                .map(line -> line.getOrder().getMarketplaceAccount().getId())
                .distinct()
                .toList();
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return marketplaceAccountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(MarketplaceAccount::getId,
                        a -> new ChannelLabel(a.getId(), a.getSeller().getSellerName(), a.getPlatform().name())));
    }

    private List<OrderLine> purchaseTargetLines() {
        // 동기화 윈도우(syncDays) 밖 주문은 상태가 갱신되지 않아 stale 결제완료로 남을 수 있으므로,
        // 구매목록 추출도 같은 윈도우(orders.ordered_at 기준)로 제한한다.
        // 🔴 판매자 필터 없음 — 동기화는 항상 전체다(PLAN 2609_29 D11).
        LocalDateTime from = LocalDate.now().minusDays(coupangProperties.getSyncDays()).atStartOfDay();
        return orderLineRepository.findRecentByStatus(PURCHASE_TARGET_STATUS, from);
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

    private PurchaseLine toLine(ShoppingListItem li, Map<Long, ChannelLabel> channels) {
        OrderLine line = li.getOrderLine();
        ChannelLabel channel = line == null
                ? null
                : channels.get(line.getOrder().getMarketplaceAccount().getId());
        return new PurchaseLine(
                li.getId(),
                line != null ? line.getId() : null,
                line != null ? "ORDER" : "MANUAL",
                line != null ? line.getOrder().getExternalOrderId() : null,
                channel != null ? channel.marketplaceAccountId() : null,
                channel != null ? channel.sellerName() : null,
                channel != null ? channel.platform() : null,
                li.getAutoQty(),
                li.getManualQty(),
                li.neededQty());
    }

    /** 결제완료인데 옵션 미매핑이거나 BOM 이 빈 주문을 vendorItemId 단위로 집계. */
    private List<UnmappedOrder> buildUnmapped() {
        List<OrderLine> lines = purchaseTargetLines();
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
