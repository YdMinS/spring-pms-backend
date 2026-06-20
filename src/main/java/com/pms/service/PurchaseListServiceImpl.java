package com.pms.service;

import com.pms.domain.OrderItem;
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
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.OrderItemRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.ShoppingListItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final String STATUS_ACCEPT = "ACCEPT";

    private final ShoppingListItemRepository shoppingListItemRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final ProductRepository productRepository;

    @Override
    @Transactional
    public void extract(Long sellerId) {
        // 1) 주문 연결 라인 autoQty 전체 리셋 → 출고/취소된 주문 라인은 아래 재적재에서 제외돼 자연히 빠짐.
        shoppingListItemRepository.resetAllAutoQty();

        // 2) ACCEPT 주문을 옵션→BOM 전개해 (order_item, product) 라인 upsert.
        for (OrderItem oi : acceptOrders(sellerId)) {
            int q = oi.purchasableQty();
            if (q <= 0) continue;

            Optional<ProductListingOption> optionOpt =
                    productListingOptionRepository.findByPlatformOptionId(oi.getExternalItemId());
            if (optionOpt.isEmpty()) continue;   // 미매핑 → 조회에서 unmapped 로 노출

            List<ProductListingProduct> boms =
                    productListingProductRepository.findByProductListingOptionId(optionOpt.get().getId());
            for (ProductListingProduct bom : boms) {
                int lineQty = q * bom.getQuantity();
                ShoppingListItem item = shoppingListItemRepository
                        .findByOrderItem_IdAndProduct_Id(oi.getId(), bom.getProduct().getId())
                        .map(existing -> existing.toBuilder().autoQty(lineQty).build())  // auto 만 교체, manual 보존
                        .orElseGet(() -> ShoppingListItem.builder()
                                .orderItem(oi)
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
        List<ShoppingListItem> items = shoppingListItemRepository.findAll();

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

            int remaining = needed - purchased;
            if (remaining > 0) {
                Product p = lineItems.get(0).getProduct();
                groups.add(new PurchaseProductGroup(
                        p.getId(), p.getProductName(), needed, purchased, remaining, lines));
            }
        }

        return new PurchaseListResponse(groups, buildUnmapped(sellerId));
    }

    @Override
    @Transactional
    public void addPurchase(Long itemId, PurchaseRecordRequest request) {
        ShoppingListItem item = shoppingListItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("ShoppingListItem", itemId));
        purchaseRecordRepository.save(PurchaseRecord.builder()
                .item(item)
                .purchasedOn(request.purchasedOn())
                .quantity(request.quantity())   // 음수 허용(정정)
                .build());
    }

    @Override
    @Transactional
    public void addManual(ManualItemRequest request) {
        ShoppingListItem item = shoppingListItemRepository
                .findByOrderItemIsNullAndProduct_Id(request.productId())
                .map(existing -> existing.toBuilder()
                        .manualQty(existing.getManualQty() + request.quantity())   // 누적
                        .build())
                .orElseGet(() -> {
                    Product product = productRepository.findById(request.productId())
                            .orElseThrow(() -> new ResourceNotFoundException("Product", request.productId()));
                    return ShoppingListItem.builder()
                            .orderItem(null)
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

    private List<OrderItem> acceptOrders(Long sellerId) {
        return sellerId == null
                ? orderItemRepository.findByStatus(STATUS_ACCEPT)
                : orderItemRepository.findByStatusAndMarketplaceAccount_Seller_Id(STATUS_ACCEPT, sellerId);
    }

    private PurchaseLine toLine(ShoppingListItem li, int linePurchased, List<PurchaseRecord> recs) {
        OrderItem oi = li.getOrderItem();
        List<PurchaseRecordView> recordViews = recs.stream()
                .map(r -> new PurchaseRecordView(r.getId(), r.getPurchasedOn(), r.getQuantity()))
                .toList();
        return new PurchaseLine(
                li.getId(),
                oi != null ? oi.getId() : null,
                oi != null ? "ORDER" : "MANUAL",
                oi != null ? oi.getExternalOrderId() : null,
                li.getAutoQty(),
                li.getManualQty(),
                linePurchased,
                recordViews);
    }

    /** ACCEPT 인데 옵션 미매핑이거나 BOM 빈 주문을 external_item_id 단위로 집계. */
    private List<UnmappedOrder> buildUnmapped(Long sellerId) {
        Map<String, List<OrderItem>> byItem = new LinkedHashMap<>();
        for (OrderItem oi : acceptOrders(sellerId)) {
            if (oi.purchasableQty() <= 0) continue;
            Optional<ProductListingOption> optionOpt =
                    productListingOptionRepository.findByPlatformOptionId(oi.getExternalItemId());
            boolean mapped = optionOpt.isPresent()
                    && !productListingProductRepository.findByProductListingOptionId(optionOpt.get().getId()).isEmpty();
            if (!mapped) {
                byItem.computeIfAbsent(oi.getExternalItemId(), k -> new ArrayList<>()).add(oi);
            }
        }
        return byItem.entrySet().stream()
                .map(e -> {
                    List<OrderItem> ois = e.getValue();
                    int qty = ois.stream().mapToInt(OrderItem::purchasableQty).sum();
                    return new UnmappedOrder(e.getKey(), ois.get(0).getItemName(), qty, ois.size());
                })
                .toList();
    }
}
