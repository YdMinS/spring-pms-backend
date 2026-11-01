package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * How many pieces of one outgoing row came out of which owner's share of an undecided intake row
 * (FEATURE_2610_19 D131).
 *
 * <p>🔴 A calculation result: {@code LotLedgerService.resplit} deletes and rewrites every row of one intake row when
 * an assignment or an owner change touches it. The ledger rows themselves are never edited for this (D125).
 *
 * <p>{@code shareRecord} = the assigned purchase record (its seller is the owner); null = the part still undecided.
 */
@Entity
@Table(name = "lot_share_usage")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LotShareUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** The undecided intake row (seller null). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "in_movement_id", nullable = false)
    private StockMovement inMovement;

    /** The outbound row that used it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "out_movement_id", nullable = false)
    private StockMovement outMovement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_record_id")
    private PurchaseRecord shareRecord;

    @Column(nullable = false)
    private Integer quantity;
}
