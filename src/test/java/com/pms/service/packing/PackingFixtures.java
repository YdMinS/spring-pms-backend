package com.pms.service.packing;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.dto.response.OutboundProductLine;
import com.pms.service.stock.OrderLineExpander;
import com.pms.service.stock.RemainingLine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 포장 콘솔 서비스 테스트가 공유하는 고정물 (FEATURE_2609_40 / 03).
 *
 * <p>배송 묶음 하나 · 박스 하나 · 라인 하나가 기본형이고, 테스트마다 필요한 축만 바꾼다.
 */
final class PackingFixtures {

    static final Long SHIPMENT_ID = 500L;
    static final Long PARCEL_ID = 900L;
    static final Long OTHER_PARCEL_ID = 901L;
    static final Long LINE_ID = 11L;
    static final Long OTHER_LINE_ID = 12L;
    static final Long PRODUCT_ID = 33L;
    static final Long OTHER_PRODUCT_ID = 34L;
    static final Long SELLER_ID = 5L;
    static final Long BOX_ID = 7L;
    static final String INVOICE = "123456789012";
    static final String BARCODE = "8801234567890";
    static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 9, 12, 10, 0);

    private PackingFixtures() {
    }

    static OrderShipment shipment() {
        Seller seller = Seller.builder().id(SELLER_ID).sellerName("셀러A").build();
        MarketplaceAccount account = MarketplaceAccount.builder().id(1L).seller(seller).build();
        Order order = Order.builder().id(7L).marketplaceAccount(account)
                .externalOrderId("ORD-1").orderedAt(ORDERED_AT).build();
        return OrderShipment.builder().id(SHIPMENT_ID).order(order).externalShipmentId("BOX-1").build();
    }

    static OrderShipment shipment(Long shipmentId, String externalOrderId) {
        OrderShipment base = shipment();
        return base.toBuilder()
                .id(shipmentId)
                .order(base.getOrder().toBuilder().externalOrderId(externalOrderId).build())
                .build();
    }

    /** 🔴 기본 상태는 {@code SHIPPED}(배송지시)다 — 포장 시점의 현실이 그렇다(D27). */
    static OrderLine line(Long id, OrderShipment shipment) {
        return line(id, shipment, OrderStatus.SHIPPED, 1, 0);
    }

    static OrderLine line(Long id, OrderShipment shipment, OrderStatus status, int orderQty, int cancelQty) {
        return OrderLine.builder()
                .id(id)
                .order(shipment.getOrder())
                .orderShipment(shipment)
                .status(status)
                .itemName("양말A 3켤레")
                .orderQty(orderQty)
                .cancelQty(cancelQty)
                .holdQty(0)
                .build();
    }

    static ShipmentParcel parcel(Long id, OrderShipment shipment, ParcelStatus status, int seq) {
        return ShipmentParcel.builder()
                .id(id)
                .orderShipment(shipment)
                .invoiceNumber(id.equals(PARCEL_ID) ? INVOICE : INVOICE + "-" + id)
                .carrierName("롯데택배")
                .parcelSeq(seq)
                .status(status)
                .build();
    }

    static Product product(Long id, String name, String barcode) {
        return Product.builder().id(id).productName(name).barcodeId(barcode).build();
    }

    static RemainingLine remaining(Long lineId, Long productId, int required, int confirmed) {
        return new RemainingLine(lineId,
                List.of(new OutboundProductLine(productId, "양말A", required, confirmed)), null);
    }

    static RemainingLine unexpanded(Long lineId) {
        return new RemainingLine(lineId, List.of(), OrderLineExpander.Failure.NO_MASTER_OPTION);
    }

    /**
     * {@code StockOutService.remaining} 스텁 — <b>넘어온 라인 그대로</b> 잔량을 만든다.
     *
     * <p>🔴 서비스가 라인을 걸러 버리면 그 라인은 여기 오지 않으므로 결과에서도 사라진다. 그래서 이 스텁이
     * D27(상태로 거르지 않는다) 회귀의 판정 장치가 된다 — 고정 목록을 돌려주면 필터링 여부를 못 본다.
     */
    static List<RemainingLine> echo(Collection<OrderLine> lines, int required, int confirmed) {
        List<RemainingLine> result = new ArrayList<>();
        for (OrderLine line : lines) {
            result.add(remaining(line.getId(), PRODUCT_ID, required, confirmed));
        }
        return result;
    }

    /** 라인마다 다른 잔량이 필요할 때 — {@code lineId → [필요, 확인]}. */
    static List<RemainingLine> echo(Collection<OrderLine> lines, Map<Long, int[]> byLine) {
        List<RemainingLine> result = new ArrayList<>();
        for (OrderLine line : lines) {
            int[] quantities = byLine.getOrDefault(line.getId(), new int[]{0, 0});
            result.add(remaining(line.getId(), PRODUCT_ID, quantities[0], quantities[1]));
        }
        return result;
    }
}
