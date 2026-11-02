package com.pms.service.packing;

import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelStatusLog;
import com.pms.repository.ShipmentParcelStatusLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Records parcel status moves (FEATURE_2611_03 / PLAN D8 · D15 · D16 · D17).
 *
 * <p><b>Purpose</b>: the <b>only component</b> that writes {@code shipment_parcel_status_log}.
 * <b>Required rule</b>: code that moves a parcel status calls {@link #record} right after saving the parcel.
 * <b>File</b>: {@code service/packing/ParcelStatusRecorder.java}
 *
 * <p>Call sites (all in {@code PackingServiceImpl}):
 * <ol>
 *   <li>{@code complete} — PENDING → PACKED</li>
 *   <li>{@code unused} — PENDING → UNUSED</li>
 *   <li>{@code restore} — UNUSED → PENDING</li>
 * </ol>
 *
 * <p><b>Usage</b>:
 * <pre>{@code
 * ShipmentParcel saved = parcelRepository.save(parcel.toBuilder().status(ParcelStatus.UNUSED).build());
 * parcelStatusRecorder.record(saved, ParcelStatus.PENDING, ParcelStatus.UNUSED, currentUsername());
 * }</pre>
 *
 * <p>🔴 <b>Moves only</b> (D16): {@code from == to} writes nothing. Parcel creation
 * ({@code ShipmentParcelRecorder}) and idempotent repeats never call this.
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
}
