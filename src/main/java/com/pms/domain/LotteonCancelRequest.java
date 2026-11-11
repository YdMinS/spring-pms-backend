package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * One LotteON customer cancel request line (FEATURE_2611_23 / D24 · R22, changeset 132-2) — the source of the order
 * line's {@code cancel_qty} (status {@value #STATUS_COMPLETED} rows), re-summed on every sync.
 *
 * <p>Key = UNIQUE(marketplace_account_id, clm_no, od_seq). {@code orderLine} is nullable: a cancel row can arrive
 * before its order line is loaded; the next sync links it by (account, odNo, odSeq).
 *
 * <p>{@code platformStatus}: LotteON {@code odPrgsStepCd} raw value — {@value #STATUS_REQUESTED} requested ·
 * {@value #STATUS_COMPLETED} cancel completed · {@value #STATUS_WITHDRAWN} withdrawn — plus our local
 * {@value #STATUS_REJECTED} after a successful [취소 거부]. Only a later 21 / 22 from LotteON overwrites
 * {@value #STATUS_REJECTED}. {@code hold_qty} is never written from here (R22).
 */
@Entity
@Table(name = "lotteon_cancel_request",
        uniqueConstraints = @UniqueConstraint(name = "uq_lotteon_cancel_request",
                columnNames = {"marketplace_account_id", "clm_no", "od_seq"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LotteonCancelRequest extends BaseEntity {

    public static final String STATUS_REQUESTED = "02";
    public static final String STATUS_COMPLETED = "21";
    public static final String STATUS_WITHDRAWN = "22";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (2609_26 D25). Hibernate sets it on INSERT and filters SELECTs — never set it by hand.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id")
    private OrderLine orderLine;

    /** LotteON claim number ({@code clmNo}) — needed by approve [60] and reject [59]. */
    @Column(name = "clm_no", nullable = false, length = 50)
    private String clmNo;

    /** LotteON order number ({@code odNo}). */
    @Column(name = "od_no", nullable = false, length = 50)
    private String odNo;

    /** LotteON order line sequence ({@code odSeq}). */
    @Column(name = "od_seq", nullable = false, length = 10)
    private String odSeq;

    /** {@code procSeq} of the claim line — sent back by approve / reject. */
    @Column(name = "proc_seq", length = 10)
    private String procSeq;

    /** {@code orglProcSeq} of the claim line — sent back by approve / reject. */
    @Column(name = "orgl_proc_seq", length = 10)
    private String orglProcSeq;

    /** Requested quantity ({@code cnclQty}). */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** {@code clmRsnCd} — named by {@code LotteonCancelReasons} (D71) and sent back by approve [60]. */
    @Column(name = "reason_code", length = 20)
    private String reasonCode;

    /** {@code clmRsnCnts}, first 1000 characters. */
    @Column(name = "reason_text", length = 1000)
    private String reasonText;

    /** {@code clmReqDttm} — KST wall clock. */
    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "platform_status", nullable = false, length = 10)
    private String platformStatus;
}
