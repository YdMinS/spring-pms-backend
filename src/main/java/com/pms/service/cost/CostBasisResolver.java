package com.pms.service.cost;

import com.pms.domain.CostBasis;
import com.pms.domain.OrderLine;
import com.pms.domain.Product;
import com.pms.domain.PurchaseRecord;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.service.stock.OrderLineExpander;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Line cost snapshot (FEATURE_2609_28 / PLAN D20, revised by 2609_29 D3).
 *
 * <p><b>The only class that bakes the cost of the moment an order line actually leaves into the line.</b> It exists so
 * that a later purchase price change does not shake past profit — joining {@code Product.price} at report time would
 * move June's net profit when June's purchase price is fixed in September, and then nobody trusts the report.
 *
 * <p>The decision is <b>per product</b>. One line uses several products through its BOM, so each product gets a grade
 * and a unit price; the line's grade is the <b>lowest</b> of them (the least exact one) and its amount is
 * Σ(unit price × quantity used). If only one of three is {@link CostBasis#LISTED}, the line is {@code LISTED} —
 * averaging would make the report look more exact than it is.
 *
 * <p>🔴 <b>There is no {@code PURCHASED} grade.</b> The {@code purchase_record → shopping_list_item} link it rested on
 * was dropped by 2609_29 (a purchase is now product × seller). See the {@link CostBasis} javadoc.
 *
 * <p>⚠️ The snapshot is baked <b>once</b>: when the line already has one, {@link #snapshot} does nothing — baking again
 * on every partial outbound would shake the line's cost as many times as it ships. The one exception is
 * {@link #refill} (FEATURE_2611_22 D10 · D20 · D22 · D25 · D37): a line whose outbound row got its lot later is costed
 * again from scratch when it is filled and when the price of that lot (or share) is typed or filled later.
 *
 * <p>⚠️ Past lines are never backfilled in a batch: their cost at that moment cannot be known now, and an invented
 * number in a report is worse than a gap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostBasisResolver {

    /** 최근 매입 1건이면 충분하다 — 등급이 "직전 매입 단가"라서 그 위는 볼 이유가 없다. */
    private static final PageRequest LATEST_ONE = PageRequest.of(0, 1);

    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ProductRepository productRepository;
    private final OrderLineRepository orderLineRepository;

    /** 판정 결과 1건. {@code amount == null} = 단가를 하나도 못 구했다(0 이 아니다). */
    public record LineCost(CostBasis basis, BigDecimal amount) {}

    /**
     * Pieces of one product that a filled row took (FEATURE_2611_22 D10 · D25): {@code unitPrice} = the lot's or the
     * share's unit price (null = empty) · {@code undecidedPart} = taken from the undecided part of an undecided lot.
     */
    public record FilledPiece(BigDecimal unitPrice, boolean undecidedPart, int quantity) {}

    /**
     * 첫 출고 시점에 원가를 굽는다. 이미 구워져 있으면 <b>건드리지 않는다</b>.
     *
     * <p>호출자는 이 메서드의 실패로 출고를 막지 않는다 — 실물은 이미 나갔고, 원가는 나중에 채울 수
     * 있지만 재고는 되돌릴 수 없다({@code StockOutServiceImpl.confirm}).
     *
     * @param products 그 라인이 소진하는 물품·수량 (BOM 전개 결과)
     */
    public void snapshot(OrderLine line, List<OrderLineExpander.ExpandedProduct> products) {
        snapshot(line, products, Map.of());
    }

    /**
     * Same as {@link #snapshot(OrderLine, List)}, with the pieces per product that the first outbound took from an
     * undecided lot (FEATURE_2610_19 D138 ①) — those pieces are costed at the registered price.
     */
    public void snapshot(OrderLine line, List<OrderLineExpander.ExpandedProduct> products,
                         Map<Long, Integer> undecidedQty) {
        if (line.getCostBasis() != null) {
            return;
        }
        LineCost cost = resolve(line, products, undecidedQty);
        orderLineRepository.save(line.toBuilder()
                .costBasis(cost.basis())
                .costAmount(cost.amount())
                .costSnapshotAt(LocalDateTime.now())
                .build());
    }

    /**
     * FEATURE_2611_22 D10 · D20 · D26: a line with a filled row costed again from scratch — {@code filled} pieces per
     * product first (unit price → grade {@code LOT_PRICE}; an empty unit price or the undecided part → the registered
     * price, grade {@code LISTED}), then {@code undecidedQty} pieces at the registered price, then the rest by the
     * purchase rule. Nothing changes when every filled piece has an empty unit price and none is an undecided part.
     */
    public void refill(OrderLine line, List<OrderLineExpander.ExpandedProduct> products,
                       Map<Long, List<FilledPiece>> filled, Map<Long, Integer> undecidedQty) {
        boolean priced = filled.values().stream().flatMap(List::stream)
                .anyMatch(piece -> piece.undecidedPart() || piece.unitPrice() != null);
        if (!priced) {
            return;
        }
        LineCost cost = resolve(line, products, filled, undecidedQty);
        orderLineRepository.save(line.toBuilder()
                .costBasis(cost.basis())
                .costAmount(cost.amount())
                .costSnapshotAt(LocalDateTime.now())
                .build());
    }

    /**
     * 물품마다 등급·단가를 구해 라인 하나로 접는다.
     *
     * <p>⚠️ 단가를 못 구한 물품은 금액에 <b>기여하지 않고</b> 등급만 {@code LISTED} 로 끌어내린다.
     * {@code 0} 으로 채우면 "원가 0" 이 되어 손익이 통째로 망가진다. 전부 못 구하면 금액은 {@code null} 이다.
     */
    LineCost resolve(OrderLine line, List<OrderLineExpander.ExpandedProduct> products) {
        return resolve(line, products, Map.of());
    }

    /**
     * FEATURE_2610_19 D138: per product, {@code undecidedQty} pieces are costed at the registered price (grade
     * {@code LISTED}, no price = left out, never 0) and only the rest follows the purchase rule below.
     */
    LineCost resolve(OrderLine line, List<OrderLineExpander.ExpandedProduct> products,
                     Map<Long, Integer> undecidedQty) {
        return resolve(line, products, Map.of(), undecidedQty);
    }

    private LineCost resolve(OrderLine line, List<OrderLineExpander.ExpandedProduct> products,
                             Map<Long, List<FilledPiece>> filled, Map<Long, Integer> undecidedQty) {
        LocalDate orderedOn = orderedOn(line);
        Long sellerId = line.getOrder().getMarketplaceAccount().getSeller().getId();
        Map<Long, Product> productsById = loadProducts(products);

        // FEATURE_2611_22 D21: LOT_PRICE > LATEST > LISTED — the line takes the lowest grade of its pieces.
        CostBasis worst = filled.isEmpty() ? CostBasis.LATEST : CostBasis.LOT_PRICE;
        BigDecimal amount = null;
        for (OrderLineExpander.ExpandedProduct expanded : products) {
            int left = expanded.quantity();
            for (FilledPiece piece : filled.getOrDefault(expanded.productId(), List.of())) {
                int qty = Math.min(left, piece.quantity());
                if (qty <= 0) {
                    continue;
                }
                left -= qty;
                BigDecimal unit = piece.undecidedPart() ? null : piece.unitPrice();
                if (unit == null) {
                    worst = CostBasis.LISTED;
                    Product product = productsById.get(expanded.productId());
                    unit = (product == null) ? null : product.getPrice();
                }
                if (unit != null) {
                    BigDecimal part = unit.multiply(BigDecimal.valueOf(qty));
                    amount = (amount == null) ? part : amount.add(part);
                }
            }
            int undecided = Math.min(left, undecidedQty.getOrDefault(expanded.productId(), 0));
            if (undecided > 0) {
                worst = CostBasis.LISTED;
                Product product = productsById.get(expanded.productId());
                BigDecimal listed = (product == null) ? null : product.getPrice();
                if (listed != null) {
                    BigDecimal part = listed.multiply(BigDecimal.valueOf(undecided));
                    amount = (amount == null) ? part : amount.add(part);
                }
            }
            int rest = left - undecided;
            if (rest <= 0) {
                continue;
            }
            BigDecimal unitPrice = latestPurchasePrice(expanded.productId(), sellerId, orderedOn);
            if (unitPrice != null && worst == CostBasis.LOT_PRICE) {
                worst = CostBasis.LATEST;
            }
            if (unitPrice == null) {
                worst = CostBasis.LISTED;
                Product product = productsById.get(expanded.productId());
                unitPrice = (product == null) ? null : product.getPrice();
            }
            if (unitPrice == null) {
                continue;   // 모르는 것은 모르는 채로 둔다 — 0 으로 치지 않는다
            }
            BigDecimal line0 = unitPrice.multiply(BigDecimal.valueOf(rest));
            amount = (amount == null) ? line0 : amount.add(line0);
        }
        return new LineCost(worst, amount);
    }

    /**
     * 그 판매자가 그 물품을 <b>주문일까지</b> 실제로 산 가격. 없으면 null → {@code LISTED} 로 내려간다.
     *
     * <p>⚠️ 주문일이 없는 라인(적재 누락)은 오늘을 기준으로 삼는다 — 기준일이 없다고 판정을 포기하면
     * 스냅샷 자체가 비고, 빈 스냅샷은 나중에 채울 방법이 없다.
     */
    private BigDecimal latestPurchasePrice(Long productId, Long sellerId, LocalDate orderedOn) {
        List<PurchaseRecord> latest =
                purchaseRecordRepository.findLatestPriced(productId, sellerId, orderedOn, LATEST_ONE);
        return latest.isEmpty() ? null : latest.get(0).getUnitPrice();
    }

    private LocalDate orderedOn(OrderLine line) {
        LocalDateTime orderedAt = line.getOrder().getOrderedAt();
        return (orderedAt == null) ? LocalDate.now() : orderedAt.toLocalDate();
    }

    /** {@code Product.price} 는 폴백에서만 쓰지만, 물품마다 조회하면 BOM 크기만큼 쿼리가 는다. */
    private Map<Long, Product> loadProducts(List<OrderLineExpander.ExpandedProduct> products) {
        List<Long> ids = products.stream()
                .map(OrderLineExpander.ExpandedProduct::productId)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
    }
}
