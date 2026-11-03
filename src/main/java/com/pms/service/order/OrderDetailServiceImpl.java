package com.pms.service.order;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.dto.response.OrderDetailResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.coupang.OrderQueryService;
import com.pms.service.packing.ParcelTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link OrderDetailService} implementation (FEATURE_2611_07 / PLAN D17 · D23 · D27 · D29 · D30 · D36 ~ D38).
 *
 * <p>🔴 The lines are mapped by {@link OrderQueryService#rows} — one list row and one detail line never differ.
 * <p>🔴 Lookups are queries (tenant filtered), never {@code findById}.
 * <p>🔴 Only a {@code PACKED} parcel carries a box and contents; {@code PENDING} and {@code UNUSED} parcels get an empty
 * box and an empty list (D37). The processed time goes out as KST through {@link ParcelTimes#utcToKst} (D38).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderDetailServiceImpl implements OrderDetailService {

    private final OrderLineRepository orderLineRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final ShipmentParcelRepository parcelRepository;
    private final ShipmentParcelItemRepository parcelItemRepository;
    private final OrderQueryService orderQueryService;

    @Override
    public OrderDetailResponse byOrderLine(Long orderLineId) {
        OrderLine line = orderLineRepository.findWithAccountAndSellerById(orderLineId)
                .orElseThrow(() -> new ResourceNotFoundException("OrderLine", orderLineId));
        return detail(line.getOrder());
    }

    @Override
    public OrderDetailResponse byParcel(Long parcelId) {
        ShipmentParcel parcel = parcelRepository.findWithOrderById(parcelId)
                .orElseThrow(() -> new ResourceNotFoundException("ShipmentParcel", parcelId));
        return detail(parcel.getOrderShipment().getOrder());
    }

    private OrderDetailResponse detail(Order order) {
        MarketplaceAccount account = order.getMarketplaceAccount();
        List<OrderLine> lines = orderLineRepository.findByOrder_IdOrderByIdAsc(order.getId());
        return new OrderDetailResponse(
                order.getExternalOrderId(),
                account.getSeller() == null ? null : account.getSeller().getSellerName(),
                order.getPlatform() == null ? null : order.getPlatform().name(),
                account.getAccountAlias(),
                order.getOrdererName(),
                order.getReceiverName(),
                order.getOrderedAt(),
                orderQueryService.rows(lines),
                parcels(order.getId()));
    }

    /** Bundle id order, then parcel sequence, then id (D29). */
    private List<OrderDetailResponse.ParcelView> parcels(Long orderId) {
        List<Long> shipmentIds = orderShipmentRepository.findByOrder_IdOrderByIdAsc(orderId).stream()
                .map(OrderShipment::getId)
                .toList();
        if (shipmentIds.isEmpty()) {
            return List.of();
        }
        List<ShipmentParcel> parcels = parcelRepository.findWithBoxByOrderShipment_IdIn(shipmentIds).stream()
                .sorted(Comparator.comparing((ShipmentParcel p) -> shipmentIds.indexOf(p.getOrderShipment().getId()))
                        .thenComparing(ShipmentParcel::getParcelSeq, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ShipmentParcel::getId))
                .toList();
        if (parcels.isEmpty()) {
            return List.of();
        }
        List<Long> packedIds = parcels.stream()
                .filter(p -> p.getStatus() == ParcelStatus.PACKED)
                .map(ShipmentParcel::getId)
                .toList();
        Map<Long, List<ShipmentParcelItem>> itemsByParcel = packedIds.isEmpty()
                ? Map.of()
                : parcelItemRepository.findByShipmentParcel_IdInOrderByIdAsc(packedIds).stream()
                        .collect(Collectors.groupingBy(item -> item.getShipmentParcel().getId(), LinkedHashMap::new,
                                Collectors.toList()));

        List<OrderDetailResponse.ParcelView> views = new ArrayList<>();
        for (ShipmentParcel parcel : parcels) {
            boolean packed = parcel.getStatus() == ParcelStatus.PACKED;
            views.add(new OrderDetailResponse.ParcelView(
                    parcel.getId(),
                    parcel.getInvoiceNumber(),
                    parcel.getCarrierName(),
                    parcel.getParcelSeq(),
                    parcel.getStatus(),
                    ParcelTimes.utcToKst(parcel.getPackedAt()),
                    packed && parcel.getBoxPackage() != null ? parcel.getBoxPackage().getType() : null,
                    packed && parcel.getBoxPackage() != null ? parcel.getBoxPackage().getId() : null,
                    packed ? items(itemsByParcel.getOrDefault(parcel.getId(), List.of())) : List.of()));
        }
        return views;
    }

    /** Merged per product, first appearance order — two lines may put the same product into one box. */
    private List<OrderDetailResponse.ParcelItemView> items(List<ShipmentParcelItem> rows) {
        Map<Long, OrderDetailResponse.ParcelItemView> byProduct = new LinkedHashMap<>();
        for (ShipmentParcelItem row : rows) {
            Long productId = row.getProduct().getId();
            int quantity = row.getQuantity() == null ? 0 : row.getQuantity();
            OrderDetailResponse.ParcelItemView seen = byProduct.get(productId);
            byProduct.put(productId, new OrderDetailResponse.ParcelItemView(productId,
                    row.getProduct().getProductName(), row.getProduct().getImageUrl(),
                    seen == null ? quantity : seen.quantity() + quantity));
        }
        return List.copyOf(byProduct.values());
    }
}
