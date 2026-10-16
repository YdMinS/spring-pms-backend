package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Single shipment request — the line picked on the order detail + the carrier the user chose and the invoice typed.
 *
 * <p>The send unit is <b>the whole box this line belongs to</b>, not the line (PLAN 2609_11 D1). The carrier is picked
 * as the marketplace code itself — Coupang has no carrier-list API and the guide's static code table is the SSOT, so
 * most carriers have no local {@code carrier} row (D2 revised 2026-09-03). Any string is filtered by the server's
 * {@link com.pms.domain.CarrierCatalog} whitelist (PLAN 2609_37 D1).
 *
 * <p>11st only (FEATURE_2610_17 / D64) — {@code mode}:
 * <ul>
 *   <li>{@code ALL} or null — the whole delivery with one invoice (1888, the default)</li>
 *   <li>{@code PARTIAL} — only {@code orderItemIds} of the delivery with one invoice (1636)</li>
 *   <li>{@code SPLIT} — the one line in {@code orderItemIds} over {@code splits} (6791, 2~10 invoices; the quantities
 *       add up to the line's order quantity). {@code invoiceNumber} is the first split's invoice</li>
 * </ul>
 * A Coupang order ignores the three fields.
 *
 * <p>Record → accessors are generated (no Lombok).
 */
public record ManualShipmentRequest(
        @NotNull(message = "주문 라인 ID는 필수입니다") Long orderItemId,
        @NotBlank(message = "택배사는 필수입니다")
        @Size(max = 30, message = "택배사 코드는 30자 이하여야 합니다") String deliveryCompanyCode,
        @NotBlank(message = "송장번호는 필수입니다")
        @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber,
        @Size(max = 10, message = "보내는 방식은 10자 이하여야 합니다") String mode,
        List<Long> orderItemIds,
        @Valid List<Split> splits) {

    /** {@code mode} values (FEATURE_2610_17 / D64). */
    public static final String MODE_ALL = "ALL";
    public static final String MODE_PARTIAL = "PARTIAL";
    public static final String MODE_SPLIT = "SPLIT";

    /** The request before FEATURE_2610_17 — the whole box with one invoice. */
    public ManualShipmentRequest(Long orderItemId, String deliveryCompanyCode, String invoiceNumber) {
        this(orderItemId, deliveryCompanyCode, invoiceNumber, null, null, null);
    }

    /** One invoice of a {@code SPLIT} send. */
    public record Split(
            @NotBlank(message = "송장번호는 필수입니다")
            @Size(max = 50, message = "송장번호는 50자 이하여야 합니다") String invoiceNumber,
            @NotNull(message = "수량이 필요합니다") @Min(value = 1, message = "수량은 1개 이상이어야 합니다")
            Integer quantity) {
    }
}
