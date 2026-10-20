package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderShipment;
import com.pms.domain.Platform;

import java.util.List;
import java.util.Set;

/**
 * Per-marketplace label-sheet rows ([송장 접수시트]) — FEATURE_2610_23 / D21, the same shape as
 * {@code ClaimSyncAdapter}. Implementations: {@link CoupangShippingLabelAdapter} · {@link ElevenstShippingLabelAdapter}.
 *
 * <p>{@link ShippingLabelServiceImpl} picks the adapter by the account's platform; a marketplace without an adapter
 * keeps today's result (skipped in the list sheet, 400 for the single-order sheet — D14). Rows are loaded into the
 * order tables best-effort inside the adapter (PLAN 2609_13 D1).
 */
public interface ShippingLabelAdapter {

    /** 택배수량(박스·라벨 수) 기본값 — 라인당 1박스. 추후 사용자 조정 가능하게 확장 예정. */
    int DEFAULT_PARCEL_QUANTITY = 1;

    /** The value compared with {@code marketplace_account.platform}. */
    Platform platform();

    /** Sheet rows of one account — the orders that wait for an invoice. A failure throws (the service isolates it). */
    List<ShippingLabelRow> collectRows(MarketplaceAccount account);

    /**
     * Sheet rows of one order (the order detail's single-order sheet). A lookup failure throws
     * {@link IllegalStateException} — never an empty sheet.
     *
     * @param orderItemId the line the screen opened — for the log only
     */
    List<ShippingLabelRow> collectOrderRows(MarketplaceAccount account, String externalOrderId, Long orderItemId);

    /**
     * Internal-stage slot (FEATURE_2610_23 / D23 · D24): rows of the 「내부 상품준비중」 bundles of one account, read
     * from the marketplace's paid list. The default throws — callers ask {@link InternalStageMarkets} first.
     *
     * @return the delivery bundle ids found in the list
     */
    default Set<String> collectInternalRows(MarketplaceAccount account, List<OrderShipment> internal,
                                            List<ShippingLabelRow> rows) {
        throw new UnsupportedOperationException(platform() + " 내부 단계 미지원");
    }

    /** addr1 + " " + addr2 결합. null 파트는 공백 처리 후 trim. */
    static String joinAddress(String addr1, String addr2) {
        String a1 = (addr1 == null) ? "" : addr1;
        String a2 = (addr2 == null) ? "" : addr2;
        return (a1 + " " + a2).trim();
    }
}
