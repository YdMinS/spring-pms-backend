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
 * 라인 원가 스냅샷 (FEATURE_2609_28 / PLAN D20, 2609_29 D3 로 개정).
 *
 * <p><b>주문 라인이 실제로 나갈 때 그 시점 원가를 라인에 굽는 유일한 클래스.</b> 이후 매입가가 바뀌어도
 * 과거 손익이 흔들리지 않게 하는 것이 존재 이유다 — 리포트 시점에 {@code Product.price} 를 조인하면
 * 6월 매입가를 9월에 고칠 때 6월 순이익이 소급 변동하고, 그러면 리포트를 아무도 믿지 않는다.
 *
 * <p>판정은 <b>물품 단위</b>다. 한 라인은 BOM 으로 여러 물품을 소진하므로 물품마다 등급·단가를 구하고,
 * 라인의 등급은 그중 <b>가장 낮은 등급</b>(가장 부정확한 쪽), 금액은 Σ(단가 × 소진 수량) 이다.
 * 3개 중 하나만 {@link CostBasis#LISTED} 여도 라인은 {@code LISTED} 다 — 평균으로 뭉개면 리포트가
 * 실제보다 정확해 보인다.
 *
 * <p>🔴 <b>{@code PURCHASED} 등급은 없다.</b> 근거였던 {@code purchase_record → shopping_list_item}
 * 링크를 2609_29 가 드롭했다(매입은 이제 물품 × 판매자). 자세한 사정은 {@link CostBasis} javadoc.
 *
 * <p>⚠️ 스냅샷은 <b>한 번만</b> 굽는다. 이미 값이 있으면 아무것도 하지 않는다 — 부분 출고마다 다시
 * 구우면 같은 라인의 원가가 출고 횟수만큼 흔들린다.
 *
 * <p>⚠️ 과거 라인을 배치로 백필하지 않는다. 그 시점 원가를 지금 알 수 없고, 지어낸 숫자가 리포트에
 * 들어가는 것이 빈칸보다 나쁘다.
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
     * 첫 출고 시점에 원가를 굽는다. 이미 구워져 있으면 <b>건드리지 않는다</b>.
     *
     * <p>호출자는 이 메서드의 실패로 출고를 막지 않는다 — 실물은 이미 나갔고, 원가는 나중에 채울 수
     * 있지만 재고는 되돌릴 수 없다({@code StockOutServiceImpl.confirm}).
     *
     * @param products 그 라인이 소진하는 물품·수량 (BOM 전개 결과)
     */
    public void snapshot(OrderLine line, List<OrderLineExpander.ExpandedProduct> products) {
        if (line.getCostBasis() != null) {
            return;
        }
        LineCost cost = resolve(line, products);
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
        LocalDate orderedOn = orderedOn(line);
        Long sellerId = line.getOrder().getMarketplaceAccount().getSeller().getId();
        Map<Long, Product> productsById = loadProducts(products);

        CostBasis worst = CostBasis.LATEST;
        BigDecimal amount = null;
        for (OrderLineExpander.ExpandedProduct expanded : products) {
            BigDecimal unitPrice = latestPurchasePrice(expanded.productId(), sellerId, orderedOn);
            if (unitPrice == null) {
                worst = CostBasis.LISTED;
                Product product = productsById.get(expanded.productId());
                unitPrice = (product == null) ? null : product.getPrice();
            }
            if (unitPrice == null) {
                continue;   // 모르는 것은 모르는 채로 둔다 — 0 으로 치지 않는다
            }
            BigDecimal line0 = unitPrice.multiply(BigDecimal.valueOf(expanded.quantity()));
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
