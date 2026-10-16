package com.pms.service;

import com.pms.dto.request.ShippingLabelExportRequest.ExportRow;
import com.pms.dto.response.InternalLabelPreview;
import com.pms.dto.response.ShippingLabelPreviewRow;

import java.util.List;

/**
 * Label-sheet generation service (generation leg).
 *
 * Reads the marketplace <b>on demand</b> — Coupang ordersheets (status=INSTRUCT) and the 11st preparing list 1635
 * (FEATURE_2610_17 / D2 · D42) — and returns the carrier intake xlsx. Only the 「내부 상품준비중」 sheet reads Coupang
 * status=ACCEPT ({@link #previewInternalRows}, FEATURE_2609_75).
 *
 * <p>❌ Receiver phone, address and delivery message are never stored in the DB — they go into the xlsx and are
 * dropped (names are stored in orders, FEATURE_2609_06).
 * <br>✅ Fetched rows are upserted into the order tables (orders · order_shipment · order_line, 2609_13 D1) — a sheet
 * order must exist in the DB when the shipment is confirmed, so fetched lines are not thrown away. Address, phone and
 * message are still not stored (D7). Saving is best-effort: a failure still returns the xlsx (D6).
 * <br>Target platforms: COUPANG and ELEVENST.
 *
 * <p>Usage (Controller):
 * <pre>{@code
 * List<ShippingLabelPreviewRow> rows = shippingLabelService.previewRows(sellerId);
 * byte[] xlsx = shippingLabelService.toXlsxFromExport(editedRows);
 * }</pre>
 */
public interface ShippingLabelService {

    /**
     * Reads the target accounts' Coupang ordersheets (INSTRUCT) and 11st preparing lists (1635), flattens them and
     * returns preview rows for editing (V2).
     *
     * @param sellerId null = every active account; set = only that seller's active accounts
     */
    List<ShippingLabelPreviewRow> previewRows(Long sellerId);

    /**
     * Preview rows of one order line's order (order_line.id).
     *
     * Coupang: the single ordersheet lookup — unlike the list-based previewRows it has no status condition, so a sheet
     * can be made from an order in any status, covering every box and line of that orderId (PLAN D2).
     * 11st: the same 1635 preparing list, keeping only that order — rows only while the order is preparing
     * (FEATURE_2610_17 / D2).
     *
     * @param orderItemId our DB order_line PK (not the marketplace order id)
     * @throws com.pms.exception.ResourceNotFoundException no such order line
     * @throws IllegalArgumentException neither a Coupang nor an 11st account's order
     * @throws IllegalStateException Coupang lookup / parse failure
     */
    List<ShippingLabelPreviewRow> previewRowsByOrder(Long orderItemId);

    /**
     * 「내부 상품준비중」 배송 묶음의 접수시트 행 (FEATURE_2609_75 / D26). 쿠팡 결제완료(ACCEPT) 목록을 조회해 거른다.
     * 기존 {@link #previewRows}(INSTRUCT)와 별개 경로다(D25 — 그쪽은 무변경).
     *
     * @param sellerId null 이면 활성 전체 계정, 지정 시 해당 셀러의 활성 계정만
     */
    InternalLabelPreview previewInternalRows(Long sellerId);

    /**
     * 사용자가 편집한 export rows 를 택배사 접수 xlsx bytes 로 변환한다 (V2).
     * 편집분(주로 parcelQuantity)을 그대로 반영한다.
     */
    byte[] toXlsxFromExport(List<ExportRow> rows);
}
