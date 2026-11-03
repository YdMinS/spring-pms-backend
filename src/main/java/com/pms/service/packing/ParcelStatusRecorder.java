package com.pms.service.packing;

import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelStatusLog;
import com.pms.repository.ShipmentParcelStatusLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Records parcel status moves and box changes (FEATURE_2611_03 / PLAN D8 · D15 · D16 · D17 · FEATURE_2611_06 /
 * PLAN D7 · D14).
 *
 * <p><b>Purpose</b>: the <b>only component</b> that writes {@code shipment_parcel_status_log}.
 * <b>Required rule</b>: code that moves a parcel status calls {@link #record} right after saving the parcel; code that
 * changes the box of a packed parcel calls {@link #recordBoxChange}.
 * <b>File</b>: {@code service/packing/ParcelStatusRecorder.java}
 *
 * <p>Call sites (all in {@code PackingServiceImpl}):
 * <ol>
 *   <li>{@code complete} — PENDING → PACKED</li>
 *   <li>{@code unused} — PENDING → UNUSED</li>
 *   <li>{@code restore} — UNUSED → PENDING</li>
 *   <li>{@code changeBox} — PACKED → PACKED (「상자 변경」, {@link #recordBoxChange})</li>
 * </ol>
 *
 * <p><b>Usage</b>:
 * <pre>{@code
 * ShipmentParcel saved = parcelRepository.save(parcel.toBuilder().status(ParcelStatus.UNUSED).build());
 * parcelStatusRecorder.record(saved, ParcelStatus.PENDING, ParcelStatus.UNUSED, currentUsername());
 * }</pre>
 *
 * <p>🔴 <b>{@link #record} writes moves only</b> (D16): {@code from == to} writes nothing. Parcel creation
 * ({@code ShipmentParcelRecorder}) and idempotent repeats never call it. The one row with the same status on both
 * sides is a box change, written only through {@link #recordBoxChange} (FEATURE_2611_06 D14).
 *
 * <p>⚠️ Exceptions are logged and swallowed, but a failed INSERT still marks the caller's transaction
 * rollback-only (it runs in that transaction), so the caller fails too — D17 holds only for failures before the save.
 *
 * <p>⚠️ Runs in the caller's transaction (no {@code REQUIRES_NEW}) — a rolled-back move leaves no row.
 *
 * <p>❌ Do not write {@code shipment_parcel_status_log} anywhere else.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParcelStatusRecorder {

    private final ShipmentParcelStatusLogRepository statusLogRepository;

    /**
     * @param parcel    the parcel after the move
     * @param from      the status before the move
     * @param to        the status after the move
     * @param changedBy who moved it (null when unauthenticated)
     */
    public void record(ShipmentParcel parcel, ParcelStatus from, ParcelStatus to, String changedBy) {
        if (parcel == null || from == null || to == null || from == to) {
            return;
        }
        try {
            statusLogRepository.save(ShipmentParcelStatusLog.builder()
                    .shipmentParcel(parcel)
                    .fromStatus(from)
                    .toStatus(to)
                    .changedBy(changedBy)
                    .build());
        } catch (RuntimeException e) {
            log.warn("[PARCEL-STATUS-LOG] failed to record parcel {} {} -> {}: {}",
                    parcel.getId(), from, to, e.getMessage());
        }
    }

    /**
     * One 「상자 변경」 row — {@code PACKED → PACKED} (FEATURE_2611_06 / PLAN D7 · D14). Same failure handling as
     * {@link #record}. A request for the same box never calls this (D4).
     *
     * @param parcel    the packed parcel after its box was changed
     * @param changedBy who changed it (null when unauthenticated)
     */
    public void recordBoxChange(ShipmentParcel parcel, String changedBy) {
        if (parcel == null) {
            return;
        }
        try {
            statusLogRepository.save(ShipmentParcelStatusLog.builder()
                    .shipmentParcel(parcel)
                    .fromStatus(ParcelStatus.PACKED)
                    .toStatus(ParcelStatus.PACKED)
                    .changedBy(changedBy)
                    .build());
        } catch (RuntimeException e) {
            log.warn("[PARCEL-STATUS-LOG] failed to record box change of parcel {}: {}",
                    parcel.getId(), e.getMessage());
        }
    }
}
