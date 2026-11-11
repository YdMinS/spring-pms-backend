package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Loads LotteON order rows ([209] · [140]) into the order core (orders · order_shipment · order_line) +
 * {@code lotteon_order_line} (FEATURE_2611_23 / D6 · D7 · D8 · D9 · D12 · D13 · D43). The LotteON counterpart of
 * {@code ElevenstOrderUpserter}.
 *
 * <p>One row = one order line, key (account, {@code odNo}, {@code odSeq}). Delivery bundle = {@link
 * LotteonOrderRows#bundleId} (D7).
 *
 * <ul>
 *   <li>D8 — only delivery lines of an order ({@code dvRtrvDvsCd} DV · {@code odTypCd} 10) with a known step are
 *       loaded; an unknown step is logged and skipped.</li>
 *   <li>R5 — status only moves forward (PAID → PREPARING → SHIPPED → DELIVERING → DELIVERED); the raw step moves
 *       on at the same rank or higher.</li>
 *   <li>D13 — amounts and shipping fees are written on the first insert only.</li>
 *   <li>D12 — the listing option is filled only while empty.</li>
 *   <li>D9 · R8 — only orderer / receiver names are kept; {@code raw} = {@link #RAW_FIELDS} only.</li>
 *   <li>Cancel quantities are not written here (a later sync step owns them).</li>
 * </ul>
 *
 * <p>⚠️ {@link #upsertRows} is the commit unit; callers call it without a transaction (same rule as
 * {@code OrderUpserter}). The account may be detached — only its id is read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteonOrderUpserter {

    /** D9: the only order-row fields kept in {@code raw}, in this order. */
    static final List<String> RAW_FIELDS = List.of(
            "odNo", "odSeq", "procSeq", "odTypCd", "odPrgsStepCd", "odSlTypCd", "spdNo", "spdNm", "sitmNo",
            "sitmNm", "eitmNo", "epdNo", "slPrc", "odQty", "slAmt", "cmbnDvPsbYn", "cmbnDvGrpNo", "dvMnsCd",
            "dvPdTypCd", "dvCst", "adtnDvCst", "fvrAmtSum", "prSfcoShrAmtSum", "prEntpShrAmtSum", "odCmptDttm",
            "owhoDttm");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int NAME_MAX_LENGTH = 100;
    private static final int ITEM_NAME_MAX_LENGTH = 500;
    private static final int OPTION_ID_MAX_DIGITS = 18;
    /** R5 forward-only order. */
    private static final List<OrderStatus> FORWARD = List.of(
            OrderStatus.PAID, OrderStatus.PREPARING, OrderStatus.SHIPPED, OrderStatus.DELIVERING,
            OrderStatus.DELIVERED);

    private final OrderRepository orderRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final OrderLineRepository orderLineRepository;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;
    private final ProductListingOptionRepository productListingOptionRepository;

    /** Rows of one call. Returns (new lines, updated lines). */
    @Transactional
    public UpsertCount upsertRows(MarketplaceAccount account, List<JsonNode> rows) {
        UpsertCount total = UpsertCount.empty();
        for (JsonNode row : rows) {
            total = total.plus(upsertRow(account, row));
        }
        return total;
    }

    private UpsertCount upsertRow(MarketplaceAccount account, JsonNode row) {
        String odNo = LotteonOrderRows.text(row, "odNo");
        String odSeq = LotteonOrderRows.text(row, "odSeq");
        if (!LotteonOrderRows.isOrderDelivery(row) || odNo == null || odSeq == null) {
            return UpsertCount.empty();
        }
        String step = LotteonOrderRows.text(row, "odPrgsStepCd");
        OrderStatus status = LotteonOrderRows.status(step);
        if (status == null) {
            log.warn("[lotteon-order] unknown step skipped: account={} odNo={} odSeq={} step={}",
                    account.getId(), odNo, odSeq, step);
            return UpsertCount.empty();
        }
        Optional<LotteonOrderLine> existing = lotteonOrderLineRepository
                .findByMarketplaceAccount_IdAndOdNoAndOdSeq(account.getId(), odNo, odSeq);
        if (existing.isPresent()) {
            updateLine(account, row, step, status, existing.get());
            return new UpsertCount(0, 1);
        }
        Order order = upsertOrder(account, odNo, row);
        OrderShipment shipment = upsertShipment(order, row);
        OrderLine line = orderLineRepository.save(OrderLine.builder()
                .order(order)
                .orderShipment(shipment)
                .status(status)
                .itemName(itemName(row))
                .orderQty(LotteonOrderRows.quantity(row, "odQty"))
                .cancelQty(0)
                .holdQty(0)
                .unitPrice(LotteonOrderRows.money(row, "slPrc"))
                .lineAmount(LotteonOrderRows.money(row, "slAmt"))
                .discountAmount(LotteonOrderRows.money(row, "fvrAmtSum"))
                .platformDiscountAmount(LotteonOrderRows.money(row, "prSfcoShrAmtSum"))
                .productListingOption(resolveOption(account, row))
                .build());
        lotteonOrderLineRepository.save(LotteonOrderLine.builder()
                .orderLine(line)
                .marketplaceAccount(account)
                .odNo(odNo)
                .odSeq(odSeq)
                .procSeq(LotteonOrderRows.text(row, "procSeq"))
                .spdNo(LotteonOrderRows.text(row, "spdNo"))
                .sitmNo(LotteonOrderRows.text(row, "sitmNo"))
                .eitmNo(LotteonOrderRows.text(row, "eitmNo"))
                .cmbnDvGrpNo(LotteonOrderRows.text(row, "cmbnDvGrpNo"))
                .platformStatus(step)
                .raw(raw(row))
                .build());
        return new UpsertCount(1, 0);
    }

    /** Existing line: quantity · name · option (if empty); status forward only (R5); amounts untouched (D13). */
    private void updateLine(MarketplaceAccount account, JsonNode row, String step, OrderStatus status,
                            LotteonOrderLine mirror) {
        OrderLine line = mirror.getOrderLine();
        OrderLine.OrderLineBuilder updated = line.toBuilder()
                .orderQty(LotteonOrderRows.quantity(row, "odQty"))
                .itemName(itemName(row))
                .productListingOption(line.getProductListingOption() != null
                        ? line.getProductListingOption()
                        : resolveOption(account, row));
        if (rank(status) > rank(line.getStatus())) {
            updated.status(status);
        }
        orderLineRepository.save(updated.build());
        LotteonOrderLine.LotteonOrderLineBuilder mirrorUpdate = mirror.toBuilder()
                .procSeq(LotteonOrderRows.text(row, "procSeq"))
                .sitmNo(LotteonOrderRows.text(row, "sitmNo"))
                .eitmNo(LotteonOrderRows.text(row, "eitmNo"))
                .raw(raw(row));
        if (rank(status) >= rank(LotteonOrderRows.status(mirror.getPlatformStatus()))) {
            mirrorUpdate.platformStatus(step);
        }
        lotteonOrderLineRepository.save(mirrorUpdate.build());
    }

    private Order upsertOrder(MarketplaceAccount account, String odNo, JsonNode row) {
        return orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(account.getId(), odNo)
                .orElseGet(() -> orderRepository.save(Order.builder()
                        .marketplaceAccount(account)
                        .platform(Platform.LOTTEON)
                        .externalOrderId(odNo)
                        // D14: LotteON order completion time read as KST wall clock.
                        .orderedAt(LotteonOrderApi.parse(LotteonOrderRows.text(row, "odCmptDttm")))
                        .ordererName(trim(LotteonOrderRows.text(row, "odrNm"), NAME_MAX_LENGTH))
                        .receiverName(trim(LotteonOrderRows.text(row, "dvpCustNm"), NAME_MAX_LENGTH))
                        .build()));
    }

    /** D7 delivery bundle; fees are written on the first insert only (D13). */
    private OrderShipment upsertShipment(Order order, JsonNode row) {
        String bundleId = LotteonOrderRows.bundleId(row);
        return orderShipmentRepository.findByOrder_IdAndExternalShipmentId(order.getId(), bundleId)
                .orElseGet(() -> orderShipmentRepository.save(OrderShipment.builder()
                        .order(order)
                        .externalShipmentId(bundleId)
                        .shippingFee(LotteonOrderRows.money(row, "dvCst"))
                        .remoteFee(LotteonOrderRows.money(row, "adtnDvCst"))
                        .trackingAvailable(true)
                        .build()));
    }

    /**
     * D12: {@code eitmNo} read as our listing option id (a LotteON option of the same sales channel) → else the first
     * option of the seller's LotteON listings whose market option id is {@code sitmNo} → else null (the purchase
     * list's unmapped group).
     */
    private ProductListingOption resolveOption(MarketplaceAccount account, JsonNode row) {
        String eitmNo = LotteonOrderRows.text(row, "eitmNo");
        if (eitmNo != null && eitmNo.length() <= OPTION_ID_MAX_DIGITS && eitmNo.chars().allMatch(Character::isDigit)) {
            Optional<ProductListingOption> ours =
                    productListingOptionRepository.findLotteonOrderOption(Long.parseLong(eitmNo), account.getId());
            if (ours.isPresent()) {
                return ours.get();
            }
        }
        String sitmNo = LotteonOrderRows.text(row, "sitmNo");
        if (sitmNo == null) {
            return null;
        }
        List<ProductListingOption> byMarketId =
                productListingOptionRepository.findLotteonOptionsByMarketId(account.getId(), sitmNo);
        return byMarketId.isEmpty() ? null : byMarketId.get(0);
    }

    /** R8: the {@link #RAW_FIELDS} present (not null) in the row, as one JSON object, in that order. */
    static String raw(JsonNode row) {
        ObjectNode kept = JSON.createObjectNode();
        for (String field : RAW_FIELDS) {
            JsonNode value = row.get(field);
            if (value != null && !value.isNull()) {
                kept.set(field, value);
            }
        }
        return kept.toString();
    }

    /** R7: {@code spdNm}, plus {@code / sitmNm} when an item name is present. */
    private static String itemName(JsonNode row) {
        String product = LotteonOrderRows.text(row, "spdNm");
        String item = LotteonOrderRows.text(row, "sitmNm");
        String name = (item == null) ? product : (product == null ? item : product + " / " + item);
        return trim(name, ITEM_NAME_MAX_LENGTH);
    }

    private static int rank(OrderStatus status) {
        return status == null ? -1 : FORWARD.indexOf(status);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
