package com.pms.service.order;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.dto.response.OrderDetailResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.coupang.OrderQueryService;
import com.pms.service.packing.ParcelTimes;
import com.pms.service.stock.OrderLineExpander;
import com.pms.service.stock.OrderLineExpander.LineExpansion;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link OrderDetailService} implementation (FEATURE_2611_07 / PLAN D17 · D23 · D27 · D29 · D30 · D36 ~ D38).
 *
 * <p>🔴 The lines are mapped by {@link OrderQueryService#rows} — one list row and one detail line never differ.
 * <p>🔴 Lookups are queries (tenant filtered), never {@code findById}.
 * <p>🔴 Only a {@code PACKED} parcel carries a box and contents; {@code PENDING} and {@code UNUSED} parcels get an empty
 * box and an empty list (D37). The processed time goes out as KST through {@link ParcelTimes#utcToKst} (D38).
 * <p>🔴 The master product · master option · components of a line come from {@link OrderLineExpander} — the same
 * expansion and Coupang fallback as stock out and the purchase list, never a name match (FEATURE_2611_15 / D9).
 * <p>{@code admin} = the caller holds {@code ROLE_ADMIN} (FEATURE_2611_15 / D20) — one judgement for both reads.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderDetailServiceImpl implements OrderDetailService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final OrderLineRepository orderLineRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final ShipmentParcelRepository parcelRepository;
    private final ShipmentParcelItemRepository parcelItemRepository;
    private final OrderQueryService orderQueryService;
    private final OrderLineExpander orderLineExpander;
    private final MasterProductOptionRepository masterProductOptionRepository;

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
                parcels(order.getId()),
                lineMasters(lines),
                isAdmin());
    }

    /** One per line, line order (D1 · D3 · D5). Quantity = per-set quantity × purchasableQty (D4 · D18). */
    private List<OrderDetailResponse.LineMaster> lineMasters(List<OrderLine> lines) {
        Map<Long, LineExpansion> expansions = orderLineExpander.expand(lines, OrderLine::purchasableQty);
        Map<Long, MasterProductOption> masterOptions = masterOptions(expansions.values());
        List<OrderDetailResponse.LineMaster> result = new ArrayList<>();
        for (OrderLine line : lines) {
            LineExpansion expansion = expansions.get(line.getId());
            if (expansion == null || expansion.failed()) {
                OrderLineExpander.Failure failure = expansion == null
                        ? OrderLineExpander.Failure.UNMAPPED_OPTION : expansion.failure();
                result.add(new OrderDetailResponse.LineMaster(line.getId(), null, null, null, List.of(),
                        failure.name()));
                continue;
            }
            MasterProductOption option = masterOptions.get(expansion.masterOptionId());
            MasterProduct master = option == null ? null : option.getMasterProduct();
            result.add(new OrderDetailResponse.LineMaster(
                    line.getId(),
                    master == null ? null : master.getId(),
                    master == null ? null : master.getName(),
                    option == null ? null : option.getName(),
                    expansion.products().stream()
                            .map(product -> new OrderDetailResponse.LineProduct(product.productId(),
                                    product.productName(), product.imageUrl(), product.quantity()))
                            .toList(),
                    null));
        }
        return result;
    }

    /** Master options of the expanded lines with their master, one query (no lazy load per line). */
    private Map<Long, MasterProductOption> masterOptions(Collection<LineExpansion> expansions) {
        List<Long> ids = expansions.stream()
                .map(LineExpansion::masterOptionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return masterProductOptionRepository.findWithMasterProductByIdIn(ids).stream()
                .collect(Collectors.toMap(MasterProductOption::getId, Function.identity(), (a, b) -> a));
    }

    /** Same judgement as {@code DashboardLayoutController#isAdmin} — the caller's authorities hold ROLE_ADMIN (D20). */
    private static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_ADMIN::equals);
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
