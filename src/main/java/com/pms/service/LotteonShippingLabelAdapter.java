package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.Platform;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderRows;
import com.pms.service.lotteon.LotteonOrderUpserter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LotteON label-sheet rows (FEATURE_2611_23 / D20 · D49 ② · R11) — the [140] progress states over the last
 * {@value #SHEET_DAYS} days in one-day calls, the same windows as the order sync. Address and phone are read, never
 * stored (D9).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteonShippingLabelAdapter implements ShippingLabelAdapter {

    /** D20: the sheet looks back this many days, one day per call. */
    static final int SHEET_DAYS = 14;

    private static final String STEP_PAID = "11";
    private static final String STEP_PREPARING = "12";

    private final LotteonOrderApi lotteonOrderApi;
    private final LotteonOrderUpserter lotteonOrderUpserter;
    private final LotteonOrderLineRepository lotteonOrderLineRepository;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /** D20: preparing (12) delivery lines; each window is loaded best-effort first (the sheet goes on). */
    @Override
    public List<ShippingLabelRow> collectRows(MarketplaceAccount account) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        List<ShippingLabelRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int back = SHEET_DAYS; back >= 1; back--) {
            LocalDateTime from = now.minusDays(back);
            LocalDateTime to = from.plusDays(1).minusSeconds(1);
            List<JsonNode> page = lotteonOrderApi.progressStates(account, from, to);
            load(account, page);
            for (JsonNode row : page) {
                if (LotteonOrderRows.isOrderDelivery(row)
                        && STEP_PREPARING.equals(LotteonOrderRows.text(row, "odPrgsStepCd"))) {
                    addRow(account, row, seen, rows);
                }
            }
        }
        return rows;
    }

    /** D20: [140] for this order only (no period), the same load and row rule; {@code orderItemId} is not used. */
    @Override
    public List<ShippingLabelRow> collectOrderRows(MarketplaceAccount account, String externalOrderId,
                                                   Long orderItemId) {
        List<JsonNode> page = lotteonOrderApi.progressStatesOfOrder(account, externalOrderId);
        load(account, page);
        List<ShippingLabelRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : page) {
            if (LotteonOrderRows.isOrderDelivery(row)
                    && STEP_PREPARING.equals(LotteonOrderRows.text(row, "odPrgsStepCd"))) {
                addRow(account, row, seen, rows);
            }
        }
        return rows;
    }

    /**
     * Internal-stage sheet (D49 ②): the same [140] windows, keeping the paid (11) and preparing (12) delivery lines of
     * the given bundles (order id + bundle id). The rows are not loaded into the order tables (R3).
     *
     * @return the delivery bundle ids found in the list
     */
    @Override
    public Set<String> collectInternalRows(MarketplaceAccount account, List<OrderShipment> internal,
                                           List<ShippingLabelRow> rows) {
        Set<String> targets = new HashSet<>();
        internal.forEach(s -> targets.add(s.getOrder().getExternalOrderId() + "|" + s.getExternalShipmentId()));
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        Set<String> found = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (int back = SHEET_DAYS; back >= 1; back--) {
            LocalDateTime from = now.minusDays(back);
            LocalDateTime to = from.plusDays(1).minusSeconds(1);
            for (JsonNode row : lotteonOrderApi.progressStates(account, from, to)) {
                String step = LotteonOrderRows.text(row, "odPrgsStepCd");
                if (!LotteonOrderRows.isOrderDelivery(row)
                        || !(STEP_PAID.equals(step) || STEP_PREPARING.equals(step))) {
                    continue;
                }
                String bundleId = LotteonOrderRows.bundleId(row);
                if (!targets.contains(LotteonOrderRows.text(row, "odNo") + "|" + bundleId)) {
                    continue;
                }
                found.add(bundleId);
                addRow(account, row, seen, rows);
            }
        }
        return found;
    }

    private void load(MarketplaceAccount account, List<JsonNode> page) {
        try {
            lotteonOrderUpserter.upsertRows(account, page);
        } catch (Exception e) {
            log.warn("롯데ON 송장시트 주문 적재 실패(시트는 계속 생성): account={}", account.getId(), e);
        }
    }

    /**
     * One LotteON row → one sheet row (D20 · R11), each ({@code odNo}, {@code odSeq}) once. Quantity = {@code odQty} −
     * the stored line's cancel quantity (0 without a stored line); 0 or less → no row.
     */
    private void addRow(MarketplaceAccount account, JsonNode row, Set<String> seen, List<ShippingLabelRow> rows) {
        String odNo = LotteonOrderRows.text(row, "odNo");
        String odSeq = LotteonOrderRows.text(row, "odSeq");
        if (!seen.add(odNo + "|" + odSeq)) {
            return;
        }
        int cancelled = lotteonOrderLineRepository
                .findByMarketplaceAccount_IdAndOdNoAndOdSeq(account.getId(), odNo, odSeq)
                .map(mirror -> {
                    OrderLine line = mirror.getOrderLine();
                    return line == null || line.getCancelQty() == null ? 0 : line.getCancelQty();
                })
                .orElse(0);
        int quantity = LotteonOrderRows.quantity(row, "odQty") - cancelled;
        if (quantity <= 0) {
            return;
        }
        String receiverPhone = value(row, "dvpMphnNo").isEmpty() ? value(row, "dvpTelNo") : value(row, "dvpMphnNo");
        rows.add(new ShippingLabelRow(
                value(row, "dvpCustNm"), receiverPhone, value(row, "dvpZipNo"),
                ShippingLabelAdapter.joinAddress(value(row, "dvpStnmZipAddr"), value(row, "dvpStnmDtlAddr")),
                value(row, "spdNm"), quantity,
                DEFAULT_PARCEL_QUANTITY, value(row, "sitmNo"),
                value(row, "odNo"), value(row, "dvMsg"), LotteonOrderRows.bundleId(row),
                account.getSeller().getSellerName(), "LOTTEON"));
    }

    /** Field text, "" when absent. */
    private static String value(JsonNode row, String field) {
        String text = LotteonOrderRows.text(row, field);
        return text == null ? "" : text;
    }
}
