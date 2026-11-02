package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * One status move of a parcel (box) — FEATURE_2611_03 / PLAN D8 · D15 · D16.
 *
 * <p>The (from, to) pair names the action, so there is no action column (D15):
 * <pre>
 *   PENDING -> PACKED  = packing done     (PackingServiceImpl.complete)
 *   PENDING -> UNUSED  = unused label     (PackingServiceImpl.unused)
 *   UNUSED  -> PENDING = unused restored  (PackingServiceImpl.restore)
 * </pre>
 *
 * <p>⚠️ Written only by {@link com.pms.service.packing.ParcelStatusRecorder}, and only when the status really
 * moved (D16) — parcel creation and idempotent repeats write nothing.
 *
 * <p>⚠️ Read-only history: there is no path that edits or deletes a row (D32).
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → the @Column definitions must match changeset 124.
 */
@Entity
@Table(name = "shipment_parcel_status_log")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ShipmentParcelStatusLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_parcel_id", nullable = false)
    private ShipmentParcel shipmentParcel;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private ParcelStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ParcelStatus toStatus;

    /** Who moved it (authentication principal). Null in an unauthenticated context. */
    @Column(name = "changed_by", length = 100)
    private String changedBy;
}
