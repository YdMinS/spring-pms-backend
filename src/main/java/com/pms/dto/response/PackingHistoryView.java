package com.pms.dto.response;

import com.pms.domain.ParcelStatus;

import java.time.LocalDateTime;

/**
 * One row of the packing history screen (FEATURE_2611_03 / PLAN D30 · FEATURE_2611_06 / PLAN D9 · D14).
 *
 * <p>The action is the ({@code fromStatus}, {@code toStatus}) pair (D15): PENDING → PACKED = 「포장 완료」 ·
 * PENDING → UNUSED = 「미사용 처리」 · UNUSED → PENDING = 「미사용 되돌리기」 · PACKED → PACKED = 「상자 변경」
 * (FEATURE_2611_06 D14). The screens turn the pair into the name.
 *
 * @param changedAt       the history row's created time as a KST wall clock (no zone in the value, D40) — the
 *                        stored UTC {@code created_date} plus 9 hours; the screens print it as is
 * @param externalOrderId the order number
 * @param changedBy       who moved it, null when unknown
 * @param boxName         the box the parcel is packed in ({@code Package.type}), null when it has none
 * @param boxPackageId    the id of that box, null when it has none — the box change dialog preselects it (box names
 *                        are not unique, FEATURE_2611_06 D9)
 */
public record PackingHistoryView(Long id, LocalDateTime changedAt, Long parcelId, String invoiceNumber,
                                 String carrierName, String externalOrderId, String sellerName,
                                 ParcelStatus fromStatus, ParcelStatus toStatus, String changedBy,
                                 String boxName, Long boxPackageId) {
}
