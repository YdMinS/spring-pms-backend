package com.pms.service;

import com.pms.dto.request.ManualShipmentRequest;
import org.springframework.web.multipart.MultipartFile;

/**
 * Shipment-confirm leg: expand order_line → marketplace invoice upload (preparing → shipped) — Coupang invoices and
 * 11st shipping 1888 (FEATURE_2610_17 / D2).
 *
 * <p>There are <b>two</b> paths:
 * <ul>
 *   <li>Bulk — upload the carrier result xlsx ({@link #confirm}). The server picks the one active carrier.</li>
 *   <li>Single manual — one line from the order detail ({@link #confirmManual}). The user picks carrier and invoice;
 *       a Coupang box already DEPARTURE or later goes as an invoice update ({@code updateInvoices}) (PLAN 2609_11 D3).
 *       11st has no update API — an already shipped 11st line is refused (D21).</li>
 * </ul>
 *
 * Follows the generation leg ({@link ShippingLabelService}): that one downloads the label sheet xlsx, this one
 * confirms shipment. Reads only, except the {@code status} of the succeeded boxes, which becomes {@code SHIPPED}
 * (PLAN 2609_07 D4) — every other field and row belongs to the sync ({@code OrderSyncFacade}).
 * ⚠️ Target platforms are COUPANG and ELEVENST — Naver etc. are a later adapter (not in scope).
 */
public interface ShipmentConfirmService {

    /**
     * Parses the carrier result xlsx and sends the invoices — Coupang in one batch per account, 11st one call per
     * delivery number — and sums the results.
     *
     * @param file the carrier's fixed xlsx (only order number col 5 and invoice number col 6 are used)
     * @return expand / group / response summary
     * @throws IllegalArgumentException empty file / parse failure (→ 400)
     */
    ShipmentConfirmResult confirm(MultipartFile file);

    /**
     * Sends the invoice the user picked (carrier + number) for the <b>box</b> of one order line — upload or update
     * (Coupang) · upload only (11st). Other boxes of the same order are untouched (PLAN 2609_11 D1). An 11st
     * {@code PARTIAL} / {@code SPLIT} request sends only its chosen lines (FEATURE_2610_17 / D64).
     *
     * @param request anchor line id · carrier code · invoice number (+ 11st {@code mode} · {@code orderItemIds} ·
     *                {@code splits}, D64)
     * @return send result (mode · success / failure detail · local status)
     * @throws IllegalArgumentException no line · neither COUPANG nor ELEVENST · no externalBoxId · carrier code not
     *                                  registered · 11st paid (D20) or already shipped (D21) · 11st PARTIAL / SPLIT
     *                                  rule (D64, R19) (→ 400)
     */
    ManualShipmentResult confirmManual(ManualShipmentRequest request);

    /**
     * 택배사 결과 xlsx 를 읽기만 한다 — 주문번호 → 송장번호(파일 순서 · 중복 제거). 전송·저장 없음.
     * [예약 발송](FEATURE_2609_75 / D27)이 {@link #confirm} 과 같은 파싱을 쓰기 위한 입구다.
     *
     * @throws IllegalArgumentException 빈 파일/파싱 실패(→ 400)
     */
    java.util.Map<String, java.util.List<String>> readInvoicesByOrderId(MultipartFile file);

    /**
     * 예약 실행 ③ — 저장된 택배사·송장으로 배송 묶음들을 쿠팡에 송장 등록한다(D27 — {@link #confirm} 과 같은 전송 헬퍼).
     * 성공 묶음만 로컬 상태를 SHIPPED 로 쓰고 {@code shipment_parcel} 을 기록한다(D22).
     */
    ReservedInvoiceResult sendReservedInvoices(java.util.List<ReservedInvoice> invoices);

    /**
     * 저장된 송장으로 [지금 발송](FEATURE_2609_75 / D18 · D27) — 결과 파일 없이 내부 단계 배송 묶음을 발주처리 → 송장 등록.
     * {@link #confirm} 의 내부 단계 분기와 <b>같은 코드</b>({@code shipInternalOrders})를 지난다.
     * 결과의 totalRows·matchedOrders = 주문 수, unmatched·skipped = 빈 목록(호출자 {@code ReservedShipmentServiceImpl} 이 채운다).
     */
    ShipmentConfirmResult shipStoredInvoices(java.util.List<ReservedInvoice> invoices);
}
