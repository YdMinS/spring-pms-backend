package com.pms.service.stock;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.Order;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.ClaimWorkRequest;
import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.ClaimProductSumView;
import com.pms.dto.response.ClaimWorkView;
import com.pms.dto.response.ShippedOrderView;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.InvoiceNumbers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link ClaimWorkService} implementation (FEATURE_2611_34 D16 ~ D23 · D25 ~ D27 · D29 ~ D31 · D34 · D39 ~ D43).
 *
 * <p>Counts per (claim × product): required = BOM quantity of one option × claim quantity (R6), done = the ledger sum
 * of that claim and product (return check-in rows, or 「교환 발송」 disposal rows), remaining = required − done, never
 * below zero.
 *
 * <p>⚠️ The ledger rules (remaining stock, seller, exchange-only) are not checked here — the ledger owns them (D34).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ClaimWorkServiceImpl implements ClaimWorkService {

    private static final int SHIPPED_DAYS = 14;
    private static final Set<OrderStatus> SHIPPED_STATUSES =
            EnumSet.of(OrderStatus.SHIPPED, OrderStatus.DELIVERING, OrderStatus.DELIVERED);

    private static final String UNKNOWN_ITEMS_RETURN =
            "이 반품은 받을 물건을 알 수 없습니다 — 입고·조정 화면의 반품 대기에서 기록하세요";
    private static final String UNKNOWN_ITEMS_EXCHANGE =
            "이 교환은 보낼 물건을 알 수 없습니다 — 판매상품의 옵션 연결을 고친 뒤 다시 여세요";
    private static final String NOT_FOUND_ORDER_CLAIMS =
            "이 주문에 반품 · 교환 건이 없습니다 — 반품/교환 화면에서 [동기화]를 먼저 누르세요";
    private static final String UNLINKED = "UNLINKED";

    private final OrderClaimRepository orderClaimRepository;
    private final StockMovementRepository stockMovementRepository;
    private final OrderLineRepository orderLineRepository;
    private final ShipmentParcelRepository shipmentParcelRepository;
    private final ProductRepository productRepository;
    private final OrderLineExpander orderLineExpander;
    private final WorkLineRecorder workLineRecorder;

    // ── lookups ──────────────────────────────────────────────────────────────

    @Override
    public List<ClaimWorkView> byReturnInvoice(String value) {
        String invoice = strip(value);
        List<OrderClaim> claims = invoice.isEmpty() ? List.of() : orderClaimRepository.findWorkByCollectInvoice(invoice);
        if (claims.isEmpty()) {
            throw new BusinessException("반품 송장을 찾을 수 없습니다: " + invoice
                    + " — 반품/교환 화면에서 [동기화]를 먼저 누르세요", HttpStatus.NOT_FOUND);
        }
        return toViews(claims, ClaimWorkKind.RETURN_IN);
    }

    @Override
    public List<ClaimWorkView> byParcelInvoice(String value) {
        String invoice = strip(value);
        ShipmentParcel parcel = (invoice.isEmpty()
                ? List.<ShipmentParcel>of()
                : shipmentParcelRepository.findByInvoiceNumberOrderByIdAsc(invoice)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException("송장번호를 찾을 수 없습니다: " + invoice, HttpStatus.NOT_FOUND));
        // Every line of the bundle: the return sync raises the cancel quantity, so cancelled lines stay in.
        List<Long> lineIds = orderLineRepository.findByOrderShipment_Id(parcel.getOrderShipment().getId()).stream()
                .map(OrderLine::getId)
                .toList();
        return toViews(claimsOnLines(lineIds), ClaimWorkKind.RETURN_IN);
    }

    @Override
    public List<ShippedOrderView> shippedOrders() {
        LocalDateTime from = LocalDate.now().minusDays(SHIPPED_DAYS).atStartOfDay();
        Map<Long, List<OrderLine>> linesByOrder = new LinkedHashMap<>();
        for (OrderLine line : orderLineRepository.findShippedSince(SHIPPED_STATUSES, from)) {
            linesByOrder.computeIfAbsent(line.getOrder().getId(), id -> new ArrayList<>()).add(line);
        }
        if (linesByOrder.isEmpty()) {
            return List.of();
        }

        List<Long> lineIds = linesByOrder.values().stream().flatMap(List::stream).map(OrderLine::getId).toList();
        List<OrderClaim> claims = orderClaimRepository.findWorkByOrderLineIds(lineIds);
        List<ClaimWorkView> views = toViews(claims, ClaimWorkKind.RETURN_IN);
        Set<Long> pendingLineIds = new HashSet<>();
        for (int i = 0; i < claims.size(); i++) {
            if (views.get(i).pending()) {
                pendingLineIds.add(claims.get(i).getOrderLine().getId());
            }
        }

        List<ShippedOrderView> result = new ArrayList<>();
        for (List<OrderLine> lines : linesByOrder.values()) {
            Order order = lines.get(0).getOrder();
            Seller seller = order.getMarketplaceAccount().getSeller();
            boolean pending = lines.stream().anyMatch(line -> pendingLineIds.contains(line.getId()));
            result.add(new ShippedOrderView(
                    order.getId(),
                    order.getExternalOrderId(),
                    order.getOrderedAt(),
                    order.getPlatform().name(),
                    order.getMarketplaceAccount().getAccountAlias(),
                    seller == null ? null : seller.getSellerName(),
                    lines.get(0).getItemName(),
                    pending));
        }
        result.sort(Comparator.comparing(ShippedOrderView::claimPending).reversed()
                .thenComparing(ShippedOrderView::orderedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ShippedOrderView::orderId, Comparator.reverseOrder()));
        return result;
    }

    @Override
    public List<ClaimWorkView> byOrder(Long orderId) {
        List<Long> lineIds = orderLineRepository.findByOrder_IdOrderByIdAsc(orderId).stream()
                .map(OrderLine::getId)
                .toList();
        return toViews(claimsOnLines(lineIds), ClaimWorkKind.RETURN_IN);
    }

    @Override
    public List<ClaimWorkView> byReshipInvoice(String value) {
        String invoice = strip(value);
        List<OrderClaim> claims = invoice.isEmpty() ? List.of() : orderClaimRepository.findWorkByReshipInvoice(invoice);
        if (claims.isEmpty()) {
            throw new BusinessException("교환 송장을 찾을 수 없습니다: " + invoice
                    + " — 반품/교환 화면에서 [동기화]를 먼저 누르세요", HttpStatus.NOT_FOUND);
        }
        return toViews(claims, ClaimWorkKind.EXCHANGE_SHIP);
    }

    @Override
    public List<ClaimWorkView> openExchanges() {
        // D42 round 5: only finished (DONE) exchanges leave — withdrawn, rejected, stale and in-review ones stay.
        List<OrderClaim> claims = orderClaimRepository.findWorkByClaimType(ClaimType.EXCHANGE).stream()
                .filter(claim -> claim.getStatus() != ClaimStatus.DONE)
                .toList();
        return toViews(claims, ClaimWorkKind.EXCHANGE_SHIP).stream()
                .filter(ClaimWorkView::pending)
                .toList();
    }

    // ── saves ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void returnIn(ClaimWorkRequest request) {
        save(request, ClaimWorkKind.RETURN_IN);
    }

    @Override
    @Transactional
    public void exchangeShip(ClaimWorkRequest request) {
        save(request, ClaimWorkKind.EXCHANGE_SHIP);
    }

    private void save(ClaimWorkRequest request, ClaimWorkKind kind) {
        workLineRecorder.requireStockLinked();

        Set<Long> claimIds = request.lines().stream()
                .map(ClaimWorkRequest.Line::orderClaimId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<OrderClaim> claims = orderClaimRepository.findWorkByIdIn(claimIds);
        Set<Long> foundIds = claims.stream().map(OrderClaim::getId).collect(Collectors.toSet());
        for (Long id : claimIds) {
            if (!foundIds.contains(id)) {
                throw new ResourceNotFoundException("OrderClaim", id);
            }
        }
        Map<Long, ClaimWorkView> views = toViews(claims, kind).stream()
                .collect(Collectors.toMap(ClaimWorkView::orderClaimId, Function.identity()));

        // Lines of the same (claim, product) are checked together against the remaining count (D21).
        Map<List<Long>, Integer> merged = new LinkedHashMap<>();
        for (ClaimWorkRequest.Line line : request.lines()) {
            merged.merge(List.of(line.orderClaimId(), line.productId()), line.quantity(), Integer::sum);
        }
        // Check every line before writing any.
        for (Map.Entry<List<Long>, Integer> entry : merged.entrySet()) {
            ClaimWorkView view = views.get(entry.getKey().get(0));
            Long productId = entry.getKey().get(1);
            if (view.failure() != null) {
                throw new IllegalArgumentException(
                        kind == ClaimWorkKind.RETURN_IN ? UNKNOWN_ITEMS_RETURN : UNKNOWN_ITEMS_EXCHANGE);
            }
            ClaimWorkView.Item item = view.items().stream()
                    .filter(candidate -> candidate.productId().equals(productId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            (kind == ClaimWorkKind.RETURN_IN ? "이 반품에 없는 물건입니다: " : "이 교환에 없는 물건입니다: ")
                                    + productId));
            if (entry.getValue() > item.remainingQty()) {
                throw new IllegalArgumentException(
                        item.productName() + "은 더 받을 수 없습니다(남은 " + item.remainingQty() + "개)");
            }
        }

        boolean returnIn = kind == ClaimWorkKind.RETURN_IN;
        for (ClaimWorkRequest.Line line : request.lines()) {
            workLineRecorder.record(new StockMovementRequest(
                    line.productId(),                                                   // productId
                    null,                                                               // sellerId — the ledger derives it from the claim
                    returnIn ? StockMovementType.RETURN_IN : StockMovementType.DISPOSAL, // movementType
                    line.quantity(),                                                    // quantity — unsigned
                    returnIn ? null : StockReason.EXCHANGE_SHIP,                        // reason
                    null,                                                               // reasonNote
                    null,                                                               // unitPrice
                    line.orderClaimId(),                                                // orderClaimId
                    null,                                                               // purchaseRecordId
                    request.movedOn()));                                                // movedOn
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The scanned value without hyphens and spaces; null becomes empty. */
    private static String strip(String value) {
        String normalized = InvoiceNumbers.normalize(value);
        return normalized == null ? "" : normalized;
    }

    /** Claims on the given lines; 404 (D27) when there is no line or no claim. */
    private List<OrderClaim> claimsOnLines(List<Long> lineIds) {
        List<OrderClaim> claims = lineIds.isEmpty() ? List.of() : orderClaimRepository.findWorkByOrderLineIds(lineIds);
        if (claims.isEmpty()) {
            throw new BusinessException(NOT_FOUND_ORDER_CLAIMS, HttpStatus.NOT_FOUND);
        }
        return claims;
    }

    /** One view per claim, in the given order. */
    private List<ClaimWorkView> toViews(List<OrderClaim> claims, ClaimWorkKind kind) {
        if (claims.isEmpty()) {
            return List.of();
        }

        Map<Long, OrderLine> lines = new LinkedHashMap<>();
        for (OrderClaim claim : claims) {
            if (claim.getOrderLine() != null) {
                lines.putIfAbsent(claim.getOrderLine().getId(), claim.getOrderLine());
            }
        }
        // One option's BOM per line; the claim quantity multiplies it below (R6).
        Map<Long, OrderLineExpander.LineExpansion> expansions = lines.isEmpty()
                ? Map.of()
                : orderLineExpander.expand(lines.values(), line -> 1);

        Set<Long> productIds = expansions.values().stream()
                .flatMap(expansion -> expansion.products().stream())
                .map(OrderLineExpander.ExpandedProduct::productId)
                .collect(Collectors.toSet());
        Map<Long, Product> products = productIds.isEmpty()
                ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<Long> claimIds = claims.stream().map(OrderClaim::getId).toList();
        List<ClaimProductSumView> sums = kind == ClaimWorkKind.RETURN_IN
                ? stockMovementRepository.findClaimProductSums(claimIds, StockMovementType.RETURN_IN, null)
                : stockMovementRepository.findClaimProductSums(claimIds, StockMovementType.DISPOSAL,
                        StockReason.EXCHANGE_SHIP);
        Map<List<Long>, Integer> done = new HashMap<>();
        for (ClaimProductSumView sum : sums) {
            done.put(List.of(sum.orderClaimId(), sum.productId()), (int) Math.abs(sum.quantity()));
        }

        List<ClaimWorkView> views = new ArrayList<>();
        for (OrderClaim claim : claims) {
            String failure;
            List<ClaimWorkView.Item> items = new ArrayList<>();
            if (claim.getOrderLine() == null) {
                failure = UNLINKED;
            } else {
                OrderLineExpander.LineExpansion expansion = expansions.get(claim.getOrderLine().getId());
                if (expansion.failed()) {
                    failure = expansion.failure().name();
                } else {
                    failure = null;
                    for (OrderLineExpander.ExpandedProduct expanded : expansion.products()) {
                        int required = expanded.quantity() * claim.getQuantity();
                        int doneQty = done.getOrDefault(List.of(claim.getId(), expanded.productId()), 0);
                        Product product = products.get(expanded.productId());
                        items.add(new ClaimWorkView.Item(
                                expanded.productId(),
                                expanded.productName(),
                                expanded.imageUrl(),
                                product == null ? null : product.getBarcodeId(),
                                required,
                                doneQty,
                                Math.max(0, required - doneQty)));
                    }
                }
            }
            Seller seller = claim.stockSeller();
            views.add(new ClaimWorkView(
                    claim.getId(),
                    claim.getClaimType().name(),
                    claim.getExternalOrderId(),
                    claim.getPlatform().name(),
                    claim.getMarketplaceAccount().getAccountAlias(),
                    seller == null ? null : seller.getSellerName(),
                    claim.getRequesterName(),
                    claim.getStatus().name(),
                    claim.getCollectStatus(),
                    claim.getItemName(),
                    claim.getQuantity(),
                    claim.getReceivedAt(),
                    failure,
                    failure == null ? items : List.of()));
        }
        return views;
    }
}
