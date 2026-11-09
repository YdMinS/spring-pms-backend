package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One day correction of a stock ledger row (FEATURE_2611_21 D7): which row, the day before and after, who corrected
 * it and when. 「일별 입출고 내역」 lists a row's corrections under its window, newest first (D24).
 *
 * <p>🔴 Written only by {@code StockDateEditService}, one per moved row — an order outbound moves every row of that
 * order on that day, so one correction writes one of these per row (D4 · D24). Never edited or deleted.
 *
 * <p>{@code editedAt} = KST wall clock (the screen shows it as it is); {@code editedBy} = the login email.
 *
 * <p>⚠️ ddl-auto=validate (dev/prod) → the @Column definitions below must match changeset 130-1.
 */
@Entity
@Table(name = "stock_movement_date_edit")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class StockMovementDateEdit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** The ledger row whose day changed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_movement_id", nullable = false)
    private StockMovement stockMovement;

    @Column(name = "old_moved_on", nullable = false)
    private LocalDate oldMovedOn;

    @Column(name = "new_moved_on", nullable = false)
    private LocalDate newMovedOn;

    /** The login email of the admin who corrected it (authentication principal); null outside a request. */
    @Column(name = "edited_by", length = 100)
    private String editedBy;

    /** When it was corrected — KST wall clock. */
    @Column(name = "edited_at", nullable = false)
    private LocalDateTime editedAt;
}
