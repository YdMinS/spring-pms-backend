package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * The LotteON side of one {@link OrderClaim} (FEATURE_2611_23 / D59 · D61, changeset 132-4) — one row per
 * {@code order_claim} row (UNIQUE {@code order_claim_id}).
 *
 * <p>Principle: information only one marketplace has lives in that marketplace's own table — {@code order_claim} gets no
 * LotteON column, and {@link OrderClaim} has no back reference (one-way, D61).
 *
 * <ul>
 *   <li>{@code procSeq} · {@code orglProcSeq}: LotteON process sequences of the claim line (from the lists and the
 *       [140] retrieval rows).</li>
 *   <li>{@code holdStatus}: {@value #HOLD} on hold · {@value #HOLD_RELEASED} released · null. The sync writes it for
 *       returns only ([51] {@code excpProcDvsCd}); exchanges change only on our hold / release success (D72).</li>
 *   <li>{@code retrievalInformedAt} · {@code reshipInformedAt}: when [210] Y was sent for the retrieval / re-ship line
 *       (null = not yet, R36).</li>
 *   <li>{@code reshipOdSeq} · {@code reshipProcSeq}: the exchange re-ship delivery line from [140] (D67).</li>
 * </ul>
 * ⚠️ No boolean, no personal data and no {@code raw} column (D61 · D9).
 */
@Entity
@Table(name = "lotteon_claim",
        uniqueConstraints = @UniqueConstraint(name = "uq_lotteon_claim_order_claim", columnNames = "order_claim_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LotteonClaim extends BaseEntity {

    /** [141] {@code excpProcDvsCd} — on hold. */
    public static final String HOLD = "03";
    /** [141] {@code excpProcDvsCd} — hold released. */
    public static final String HOLD_RELEASED = "09";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (2609_26 D25). Hibernate sets it on INSERT and filters SELECTs — never set it by hand.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_claim_id", nullable = false, unique = true)
    private OrderClaim orderClaim;

    @Column(name = "proc_seq", length = 10)
    private String procSeq;

    @Column(name = "orgl_proc_seq", length = 10)
    private String orglProcSeq;

    @Column(name = "hold_status", length = 10)
    private String holdStatus;

    @Column(name = "retrieval_informed_at")
    private LocalDateTime retrievalInformedAt;

    @Column(name = "reship_od_seq", length = 10)
    private String reshipOdSeq;

    @Column(name = "reship_proc_seq", length = 10)
    private String reshipProcSeq;

    @Column(name = "reship_informed_at")
    private LocalDateTime reshipInformedAt;
}
