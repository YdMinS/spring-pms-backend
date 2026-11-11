package com.pms.service;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderShipment;
import com.pms.domain.Platform;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstOrderList;
import com.pms.service.elevenst.ElevenstOrderUpserter;
import com.pms.service.elevenst.ElevenstPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 11st label-sheet rows (FEATURE_2610_17 / D2 · D42) — the 1635 preparing list, moved from
 * {@link ShippingLabelServiceImpl} unchanged (FEATURE_2610_23 / D3 · D21). The internal-stage slot reads the 1876
 * paid list (FEATURE_2611_23 / D51 ②). Address and phone are read, never stored (D7).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstShippingLabelAdapter implements ShippingLabelAdapter {

    /** FEATURE_2610_17: 11st order lists accept at most 7 days per call. */
    private static final int ELEVENST_WINDOW_DAYS = 7;

    private final CoupangProperties coupangProperties;
    private final ElevenstOrderApi elevenstOrderApi;
    private final ElevenstOrderUpserter elevenstOrderUpserter;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    @Override
    public List<ShippingLabelRow> collectRows(MarketplaceAccount account) {
        return collectElevenstRows(account, null);
    }

    /** FEATURE_2610_17 / D2: the same 1635 list, keeping only this order — only a preparing order has rows. */
    @Override
    public List<ShippingLabelRow> collectOrderRows(MarketplaceAccount account, String externalOrderId,
                                                   Long orderItemId) {
        return collectElevenstRows(account, externalOrderId);
    }

    /**
     * Internal-stage sheet (FEATURE_2611_23 / D51 ②): the 1876 paid list from the day before the oldest order of the
     * bundles (0:00 KST; today − 1 day when no order time is known) until now, in 7-day calls, keeping only the rows
     * whose delivery number ({@code dlvNo}) is one of the bundles' {@code external_shipment_id}. Each
     * ({@code ordNo}, {@code ordPrdSeq}) row is taken once and filled with the same 11st sheet rule. The rows are not
     * loaded into the order tables (R3).
     *
     * @return the delivery numbers found in the list
     */
    @Override
    public Set<String> collectInternalRows(MarketplaceAccount account, List<OrderShipment> internal,
                                           List<ShippingLabelRow> rows) {
        Set<String> targetDlvNos = new HashSet<>();
        internal.forEach(s -> targetDlvNos.add(s.getExternalShipmentId()));
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        LocalDate oldest = internal.stream()
                .map(s -> s.getOrder().getOrderedAt())
                .filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .min(Comparator.naturalOrder())
                .orElse(now.toLocalDate());
        LocalDateTime start = oldest.minusDays(1).atStartOfDay();
        Set<String> found = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (LocalDateTime from = start; from.isBefore(now); from = from.plusDays(ELEVENST_WINDOW_DAYS)) {
            LocalDateTime to = from.plusDays(ELEVENST_WINDOW_DAYS).isAfter(now) ? now : from.plusDays(ELEVENST_WINDOW_DAYS);
            List<Map<String, String>> page = elevenstOrderApi.listByMinute(account, ElevenstPaths.ORDERS_PAID,
                    "ordPrdSeq", from, to);
            for (Map<String, String> row : page) {
                String dlvNo = value(row, "dlvNo");
                if (!targetDlvNos.contains(dlvNo)) {
                    continue;
                }
                if (seen.add(value(row, "ordNo") + "|" + value(row, "ordPrdSeq"))) {
                    found.add(dlvNo);
                    flattenElevenstRow(account, row, rows);
                }
            }
        }
        return found;
    }

    /**
     * 11st sheet rows (FEATURE_2610_17 / D2 · D42): the 1635 preparing list over the same look-back as the Coupang
     * sheet ({@code oklyx.coupang.instruct-days}), cut into 7-day calls (11st order lists accept 7 days at most).
     * Rows are loaded into the order tables best-effort (same rule as the Coupang sheet — a sheet order must exist
     * in the DB); address and phone go to the sheet only (D7).
     *
     * @param onlyOrderNo when set, only that order's rows (single-order sheet)
     */
    private List<ShippingLabelRow> collectElevenstRows(MarketplaceAccount account, String onlyOrderNo) {
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        LocalDateTime start = now.minusDays(coupangProperties.getInstructDays());
        List<ShippingLabelRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LocalDateTime from = start; from.isBefore(now); from = from.plusDays(ELEVENST_WINDOW_DAYS)) {
            LocalDateTime to = from.plusDays(ELEVENST_WINDOW_DAYS).isAfter(now) ? now : from.plusDays(ELEVENST_WINDOW_DAYS);
            List<Map<String, String>> page = elevenstOrderApi.listByMinute(account, ElevenstPaths.ORDERS_PREPARING,
                    "ordPrdSeq", from, to);
            try {
                elevenstOrderUpserter.upsertRows(account, ElevenstOrderList.PREPARING, page);
            } catch (Exception e) {
                log.warn("11번가 송장시트 주문 적재 실패(시트는 계속 생성): account={}", account.getId(), e);
            }
            for (Map<String, String> row : page) {
                String orderNo = value(row, "ordNo");
                if (onlyOrderNo != null && !onlyOrderNo.equals(orderNo)) {
                    continue;
                }
                if (seen.add(orderNo + "|" + value(row, "ordPrdSeq"))) {
                    flattenElevenstRow(account, row, rows);
                }
            }
        }
        return rows;
    }

    /** One 11st order row → one sheet row, the D42 field mapping; quantity 0 or less → no row. */
    private void flattenElevenstRow(MarketplaceAccount account, Map<String, String> row, List<ShippingLabelRow> rows) {
        int quantity;
        try {
            quantity = Integer.parseInt(value(row, "ordQty"));
        } catch (NumberFormatException e) {
            quantity = 0;
        }
        if (quantity <= 0) {
            return;
        }
        String phone = value(row, "rcvrPrtblNo").isBlank() ? value(row, "rcvrTlphn") : value(row, "rcvrPrtblNo");
        rows.add(new ShippingLabelRow(
                value(row, "rcvrNm"), phone, value(row, "rcvrMailNo"),
                ShippingLabelAdapter.joinAddress(value(row, "rcvrBaseAddr"), value(row, "rcvrDtlsAddr")),
                value(row, "prdNm"), quantity,
                DEFAULT_PARCEL_QUANTITY, value(row, "prdStckNo"),
                value(row, "ordNo"), value(row, "ordDlvReqCont"), value(row, "dlvNo"),
                account.getSeller().getSellerName(), account.getPlatform().name()));
    }

    /** Field text, "" when absent (same as the Coupang {@code asText("")}). */
    private static String value(Map<String, String> row, String field) {
        String text = row.get(field);
        return text == null ? "" : text.trim();
    }
}
