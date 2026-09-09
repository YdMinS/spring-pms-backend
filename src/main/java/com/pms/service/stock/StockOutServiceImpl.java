package com.pms.service.stock;

import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.dto.request.OutboundConfirmRequest;
import com.pms.dto.response.OutboundOrderView;
import com.pms.dto.response.OutboundProductLine;
import com.pms.dto.response.OutboundResponse;
import com.pms.dto.response.OutboundUnexpandedView;
import com.pms.dto.response.StockMovementView;
import com.pms.dto.response.StockOutSumView;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link StockOutService} 구현.
 *
 * <p>수량 규칙이 이 클래스의 전부다:
 * <pre>
 *   필요 수량 = 마스터 BOM 수량 × <b>orderQty</b>          (OrderLineExpander)
 *   확인 수량 = 그 라인·물품의 STOCK_OUT 합계 (양수로 뒤집음)
 *   남은 수량 = 필요 − 확인
 * </pre>
 *
 * <p>🔴 <b>{@code cancelQty} 를 소진 계산에 쓰지 않는다.</b> 반품 동기화가 그 컬럼을 올리므로
 * ({@code CoupangReturnSyncServiceImpl:369}) {@code purchasableQty()} 로 필요 수량을 잡으면
 * <b>반품이 들어올 때마다 출고량이 저절로 줄어든다</b> — 창고에 물건을 되돌려 놓은 사람이 없는데도.
 * 소진의 정본은 {@code STOCK_OUT} 합계다(D12). 전량 취소만 목록에서 뺀다(아래 {@code effectiveStatus}).
 *
 * <p>🔴 전개 실패 라인은 {@code unexpanded} 로 <b>드러내고</b>, 확인은 거부한다(400) — 무엇을 빼야 할지
 * 모르는 채로 재고를 깎지 않는다(D13).
 *
 * <p>⚠️ 클래스 기본 readOnly, {@link #confirm} 만 쓰기 트랜잭션. 원장은 append-only 다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockOutServiceImpl implements StockOutService {

    /** 아직 안 나간 것 = 결제완료 · 상품준비중. 종결 상태({@code isTerminal()})는 작업 대상이 아니다. */
    private static final List<OrderStatus> OUTBOUND_STATUSES = List.of(OrderStatus.PAID, OrderStatus.PREPARING);

    private final OrderLineRepository orderLineRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;
    private final OrderLineExpander orderLineExpander;

    @Override
    public OutboundResponse outbound(Long sellerId, OrderStatus status) {
        List<OrderStatus> statuses = statuses(status);
        List<OrderLine> lines = orderLineRepository.findOutboundTargets(statuses, sellerId).stream()
                // 전량 취소는 저장되는 상태가 아니라 파생값이다 — 상태 컬럼만 보면 취소된 주문이 목록에 남는다.
                .filter(line -> !line.effectiveStatus().isTerminal())
                .toList();
        if (lines.isEmpty()) {
            return new OutboundResponse(List.of(), List.of());
        }

        // 필요 수량의 배수는 orderQty 다 — 위 클래스 주석의 cancelQty 함정 참고.
        Map<Long, OrderLineExpander.LineExpansion> expansions =
                orderLineExpander.expand(lines, OrderLine::getOrderQty);
        Map<Long, Map<Long, Long>> confirmed = confirmedByLine(lines.stream().map(OrderLine::getId).toList());

        List<OutboundOrderView> orders = new ArrayList<>();
        List<OutboundUnexpandedView> unexpanded = new ArrayList<>();
        for (OrderLine line : lines) {
            OrderLineExpander.LineExpansion expansion = expansions.get(line.getId());
            if (expansion == null || expansion.failed()) {
                unexpanded.add(new OutboundUnexpandedView(line.getId(),
                        line.getOrder().getExternalOrderId(), line.getItemName(),
                        expansion == null
                                ? OrderLineExpander.Failure.UNMAPPED_OPTION.name()
                                : expansion.failure().name()));
                continue;
            }

            Map<Long, Long> confirmedForLine = confirmed.getOrDefault(line.getId(), Map.of());
            List<OutboundProductLine> products = expansion.products().stream()
                    .map(p -> new OutboundProductLine(p.productId(), p.productName(), p.quantity(),
                            confirmedQty(confirmedForLine, p.productId())))
                    .toList();
            // 다 내보낸 라인은 목록에서 뺀다 — 남은 것이 하나도 없으면 화면에 띄울 이유가 없다.
            if (products.stream().allMatch(p -> p.remainingQty() <= 0)) {
                continue;
            }

            Seller seller = sellerOf(line);
            orders.add(new OutboundOrderView(
                    line.getId(),
                    line.getOrder().getExternalOrderId(),
                    line.getItemName(),
                    line.getStatus(),
                    line.getOrder().getOrderedAt(),
                    seller.getId(),
                    seller.getSellerName(),
                    line.getOrderQty(),
                    products));
        }
        return new OutboundResponse(orders, unexpanded);
    }

    @Override
    @Transactional
    public List<StockMovementView> confirm(OutboundConfirmRequest request) {
        OrderLine line = orderLineRepository.findWithListingOptionById(request.orderLineId())
                .orElseThrow(() -> new ResourceNotFoundException("OrderLine", request.orderLineId()));

        OrderLineExpander.LineExpansion expansion =
                orderLineExpander.expand(List.of(line), OrderLine::getOrderQty).get(line.getId());
        if (expansion == null || expansion.failed()) {
            // 무엇을 뺄지 모르는 채로 재고를 깎지 않는다(D13). 사람이 매핑을 고친 뒤 다시 확인한다.
            throw new IllegalArgumentException("구성 물품을 전개할 수 없는 주문입니다");
        }

        Map<Long, Integer> requiredByProduct = new LinkedHashMap<>();
        for (OrderLineExpander.ExpandedProduct p : expansion.products()) {
            requiredByProduct.put(p.productId(), p.quantity());
        }
        Map<Long, Long> confirmedForLine =
                confirmedByLine(List.of(line.getId())).getOrDefault(line.getId(), Map.of());
        Seller seller = sellerOf(line);

        List<StockMovementView> saved = new ArrayList<>();
        for (OutboundConfirmRequest.ConfirmLine confirmLine : request.lines()) {
            int quantity = confirmLine.quantity();
            if (quantity <= 0) {
                throw new IllegalArgumentException("수량은 0보다 커야 합니다");
            }
            Integer required = requiredByProduct.get(confirmLine.productId());
            if (required == null) {
                throw new IllegalArgumentException("이 주문의 구성 물품이 아닙니다");
            }
            int remaining = required - confirmedQty(confirmedForLine, confirmLine.productId());
            if (quantity > remaining) {
                // 동시성 가드는 여기까지다(PLAN "남는 위험") — 두 화면이 동시에 확인하면 두 번째가 막힌다.
                throw new IllegalArgumentException("남은 출고 수량을 초과했습니다");
            }

            Product product = productRepository.findById(confirmLine.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", confirmLine.productId()));
            StockMovement movement = stockMovementRepository.save(StockMovement.builder()
                    .product(product)
                    .seller(seller)
                    .movementType(StockMovementType.STOCK_OUT)
                    // 부호는 서버가 정한다 — 요청은 "3개 내보냄"이고 원장은 -3 이다.
                    .quantity(-quantity)
                    .location(StockLocationPolicy.resolve(product))
                    // reason 없음(D7): STOCK_OUT 은 주문이라는 컨텍스트가 곧 사유다.
                    // unitPrice 없음: 출고 원가는 cost_basis 가 결정한다.
                    .orderLine(line)
                    .movedOn(request.movedOn())
                    .createdBy(currentUsername())
                    .build());
            saved.add(toView(movement, seller));
        }
        return saved;
    }

    /** {@code status} 가 오면 그것 하나만, 없으면 둘 다. 종결 상태는 거부한다(작업 대상이 아니다). */
    private List<OrderStatus> statuses(OrderStatus status) {
        if (status == null) {
            return OUTBOUND_STATUSES;
        }
        if (!OUTBOUND_STATUSES.contains(status)) {
            throw new IllegalArgumentException("출고 대상 상태가 아닙니다");
        }
        return List.of(status);
    }

    /** 라인 id → (물품 id → 이미 확인한 수량, <b>양수</b>). */
    private Map<Long, Map<Long, Long>> confirmedByLine(List<Long> orderLineIds) {
        Map<Long, Map<Long, Long>> byLine = new LinkedHashMap<>();
        for (StockOutSumView sum : stockMovementRepository.findStockOutSums(orderLineIds)) {
            // 원장은 -n 으로 저장한다. 뒤집는 것은 여기 한 곳뿐이다 — 화면이 부호를 다시 만지면
            // 웹과 모바일이 갈린다.
            byLine.computeIfAbsent(sum.orderLineId(), k -> new LinkedHashMap<>())
                    .put(sum.productId(), Math.abs(sum.quantity()));
        }
        return byLine;
    }

    private int confirmedQty(Map<Long, Long> confirmedForLine, Long productId) {
        return confirmedForLine.getOrDefault(productId, 0L).intValue();
    }

    /**
     * 출고 행의 판매자 (PLAN 2609_29 D4). 출고는 주문에서 출발하므로(D11)
     * {@code orderLine → order → marketplaceAccount → seller} 로 유도한다 — 화면이 묻지 않는다.
     */
    private Seller sellerOf(OrderLine line) {
        return line.getOrder().getMarketplaceAccount().getSeller();
    }

    private StockMovementView toView(StockMovement m, Seller seller) {
        return new StockMovementView(
                m.getId(),
                m.getProduct().getId(),
                m.getProduct().getProductName(),
                seller.getId(),
                seller.getSellerName(),
                m.getMovementType(),
                m.getQuantity(),
                m.getReason(),
                m.getReasonNote(),
                m.getUnitPrice(),
                m.getOrderLine() == null ? null : m.getOrderLine().getId(),
                null,
                null,
                m.getMovedOn(),
                m.getCreatedBy());
    }

    /** Same source as StockLedgerServiceImpl / OrderCancelServiceImpl (PLAN 2609_28 D9). */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null) ? null : auth.getName();
    }
}
