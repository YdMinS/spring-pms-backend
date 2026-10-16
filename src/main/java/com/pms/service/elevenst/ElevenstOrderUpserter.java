package com.pms.service.elevenst;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads 11st order list rows into the order core (orders · order_shipment · order_line) + {@code elevenst_order_line}
 * (FEATURE_2610_17 / D5 · D6 · D17 · D31 · D33 · D43). The 11st counterpart of {@code OrderUpserter}.
 *
 * <p>One row = one order line, key (account, {@code ordNo}, {@code ordPrdSeq}). Delivery bundle =
 * {@code dlvNo} (several orders may share it — the bundle row is per order, UNIQUE (order, external_shipment_id)).
 *
 * <ul>
 *   <li>D6 — status = the list the row came from; status only moves forward (PAID → PREPARING → SHIPPED →
 *       DELIVERED), a later list never moves a line back. The shipping / delivered lists only update existing
 *       lines.</li>
 *   <li>D31 — amounts and shipping fees are written on the first insert only.</li>
 *   <li>D17 — the listing option comes from {@code sellerStockCd}; filled only while empty.</li>
 *   <li>D7 · D43 — only orderer / receiver names are kept; {@code raw} = {@link #RAW_FIELDS} only.</li>
 *   <li>hold / cancel quantities are not written here — {@link ElevenstCancelRequestUpserter} re-sums them (D40).</li>
 * </ul>
 *
 * <p>⚠️ {@link #upsertRows} is the commit unit; callers call it without a transaction (same rule as
 * {@code OrderUpserter}). The account may be detached — only its id is read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElevenstOrderUpserter {

    /** D43: the only order-row fields kept in {@code raw}, in this order. */
    static final List<String> RAW_FIELDS = List.of(
            "ordNo", "ordPrdSeq", "dlvNo", "bndlDlvSeq", "bndlDlvYN", "prdNo", "prdNm", "prdStckNo",
            "slctPrdOptNm", "sellerPrdCd", "sellerStockCd", "ordQty", "selPrc", "ordAmt", "ordOptWonStl",
            "lstSellerDscPrc", "lstTmallDscPrc", "sellerDscPrc", "tmallDscPrc", "ordPayAmt", "stlPlnAmt", "dlvCst",
            "dlvCstType", "bmDlvCst", "bmDlvCstType", "ordDt", "ordStlEndDt", "plcodrCnfDt", "addPrdYn", "addPrdNo",
            "dlvSndDue", "delaySendDt", "gblDlvYn", "visitDlvYn", "sendGiftYn");

    private static final int NAME_MAX_LENGTH = 100;
    private static final int ITEM_NAME_MAX_LENGTH = 500;
    /** D6 forward-only order. */
    private static final List<OrderStatus> FORWARD = List.of(
            OrderStatus.PAID, OrderStatus.PREPARING, OrderStatus.SHIPPED, OrderStatus.DELIVERING,
            OrderStatus.DELIVERED);

    private final OrderRepository orderRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final OrderLineRepository orderLineRepository;
    private final ElevenstOrderLineRepository elevenstOrderLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;

    /** Rows of one list call. Returns (new lines, updated lines). */
    @Transactional
    public UpsertCount upsertRows(MarketplaceAccount account, ElevenstOrderList list, List<Map<String, String>> rows) {
        UpsertCount total = UpsertCount.empty();
        for (Map<String, String> row : rows) {
            total = total.plus(upsertRow(account, list, row));
        }
        return total;
    }

    private UpsertCount upsertRow(MarketplaceAccount account, ElevenstOrderList list, Map<String, String> row) {
        String ordNo = text(row, "ordNo");
        String ordPrdSeq = text(row, "ordPrdSeq");
        if (ordNo == null || ordPrdSeq == null) {
            log.warn("[11st-order] row without ordNo/ordPrdSeq skipped: list={}", list);
            return UpsertCount.empty();
        }
        Optional<ElevenstOrderLine> existing = elevenstOrderLineRepository
                .findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(account.getId(), ordNo, ordPrdSeq);
        if (existing.isPresent()) {
            updateLine(account, list, row, existing.get());
            return new UpsertCount(0, 1);
        }
        if (!list.createsLines()) {
            // D6: the shipping / delivered lists carry no quantity or amount — they never create a line.
            return UpsertCount.empty();
        }
        Order order = upsertOrder(account, ordNo, row);
        OrderShipment shipment = upsertShipment(order, row);
        OrderLine line = orderLineRepository.save(OrderLine.builder()
                .order(order)
                .orderShipment(shipment)
                .status(list.status())
                .itemName(itemName(row))
                .orderQty(intValue(row, "ordQty"))
                .cancelQty(0)
                .holdQty(0)
                .unitPrice(money(row, "selPrc"))
                .lineAmount(money(row, "ordAmt"))
                .discountAmount(sum(money(row, "lstSellerDscPrc"), money(row, "lstTmallDscPrc")))
                .platformDiscountAmount(money(row, "lstTmallDscPrc"))
                .productListingOption(resolveOption(account, row))
                .build());
        elevenstOrderLineRepository.save(ElevenstOrderLine.builder()
                .orderLine(line)
                .marketplaceAccount(account)
                .ordNo(ordNo)
                .ordPrdSeq(ordPrdSeq)
                .prdNo(text(row, "prdNo"))
                .prdStckNo(text(row, "prdStckNo"))
                .sellerStockCd(text(row, "sellerStockCd"))
                .platformStatus(list.code())
                .raw(raw(row))
                .build());
        return new UpsertCount(1, 0);
    }

    /** Existing line: status forward only (D6); quantity · name · option (if empty) only from lists that carry them. */
    private void updateLine(MarketplaceAccount account, ElevenstOrderList list, Map<String, String> row,
                            ElevenstOrderLine mirror) {
        OrderLine line = mirror.getOrderLine();
        boolean forward = rank(list.status()) > rank(line.getStatus());
        OrderLine.OrderLineBuilder updated = line.toBuilder();
        if (forward) {
            updated.status(list.status());
        }
        if (list.createsLines()) {
            updated.orderQty(intValue(row, "ordQty"))
                    .itemName(itemName(row))
                    .productListingOption(line.getProductListingOption() != null
                            ? line.getProductListingOption()
                            : resolveOption(account, row));
        }
        orderLineRepository.save(updated.build());
        if (forward || list.createsLines()) {
            ElevenstOrderLine.ElevenstOrderLineBuilder mirrorUpdate = mirror.toBuilder();
            if (forward) {
                mirrorUpdate.platformStatus(list.code());
            }
            if (list.createsLines()) {
                mirrorUpdate.prdStckNo(text(row, "prdStckNo"))
                        .sellerStockCd(text(row, "sellerStockCd"))
                        .raw(raw(row));
            }
            elevenstOrderLineRepository.save(mirrorUpdate.build());
        }
    }

    private Order upsertOrder(MarketplaceAccount account, String ordNo, Map<String, String> row) {
        String ordererName = trim(text(row, "ordNm"), NAME_MAX_LENGTH);
        String receiverName = trim(text(row, "rcvrNm"), NAME_MAX_LENGTH);
        return orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(account.getId(), ordNo)
                .orElseGet(() -> orderRepository.save(Order.builder()
                        .marketplaceAccount(account)
                        .platform(Platform.ELEVENST)
                        .externalOrderId(ordNo)
                        // D32: 11st payment time (ordStlEndDt) read as KST wall clock.
                        .orderedAt(ElevenstTimes.parse(text(row, "ordStlEndDt")))
                        .ordererName(ordererName)
                        .receiverName(receiverName)
                        .build()));
    }

    /** Delivery bundle = {@code dlvNo}; blank → no bundle row (same rule as the Coupang box id). */
    private OrderShipment upsertShipment(Order order, Map<String, String> row) {
        String dlvNo = text(row, "dlvNo");
        if (dlvNo == null) {
            return null;
        }
        return orderShipmentRepository.findByOrder_IdAndExternalShipmentId(order.getId(), dlvNo)
                .orElseGet(() -> orderShipmentRepository.save(OrderShipment.builder()
                        .order(order)
                        .externalShipmentId(dlvNo)
                        // D31: written on the first insert only.
                        .shippingFee(money(row, "dlvCst"))
                        .remoteFee(money(row, "bmDlvCst"))
                        .trackingAvailable(true)
                        .build()));
    }

    /**
     * D17: {@code sellerStockCd} read as our listing option id; kept only when it is an 11st option of the same
     * sales channel for this {@code prdNo}. Anything else (not a number, missing, another channel, a product
     * registered outside oclyx) → null, and the line shows under the purchase list's unmapped group.
     */
    private ProductListingOption resolveOption(MarketplaceAccount account, Map<String, String> row) {
        String sellerStockCd = text(row, "sellerStockCd");
        String prdNo = text(row, "prdNo");
        if (sellerStockCd == null || prdNo == null || !sellerStockCd.chars().allMatch(Character::isDigit)
                || sellerStockCd.length() > 18) {
            return null;
        }
        return productListingOptionRepository
                .findElevenstOrderOption(Long.parseLong(sellerStockCd), account.getId(), prdNo)
                .orElse(null);
    }

    /** D43: allow-listed fields only, as {@code <order><field>value</field>…</order>}. */
    static String raw(Map<String, String> row) {
        StringBuilder xml = new StringBuilder("<order>");
        for (String field : RAW_FIELDS) {
            String value = row.get(field);
            if (value != null) {
                ElevenstXml.element(xml, field, value);
            }
        }
        return xml.append("</order>").toString();
    }

    /** Order-list item name = {@code prdNm}, plus {@code / slctPrdOptNm} when an option name is present. */
    private static String itemName(Map<String, String> row) {
        String product = text(row, "prdNm");
        String option = text(row, "slctPrdOptNm");
        String name = (option == null) ? product : (product == null ? option : product + " / " + option);
        return trim(name, ITEM_NAME_MAX_LENGTH);
    }

    private static int rank(OrderStatus status) {
        return status == null ? -1 : FORWARD.indexOf(status);
    }

    private static String text(Map<String, String> row, String field) {
        String value = row.get(field);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static int intValue(Map<String, String> row, String field) {
        BigDecimal value = money(row, field);
        return value == null ? 0 : value.intValue();
    }

    /** 11st amounts are plain decimal text; blank or unreadable → null (unknown, not 0). */
    private static BigDecimal money(Map<String, String> row, String field) {
        String value = text(row, field);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? BigDecimal.ZERO : a).add(b == null ? BigDecimal.ZERO : b);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
