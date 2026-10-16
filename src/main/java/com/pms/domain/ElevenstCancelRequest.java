package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * One 11st cancel claim (FEATURE_2610_17 / D40, changeset 108) — the source of the order line's
 * {@code hold_qty} (requests still open) and {@code cancel_qty} (completed), re-summed on every sync.
 *
 * <p>Key = UNIQUE(marketplace_account_id, ord_prd_cn_seq). {@code orderLine} is nullable: a cancel row can arrive
 * before its order line is loaded; the next sync links it by (account, ordNo, ordPrdSeq).
 *
 * <p>{@code platformStatus}: 11st {@code ordCnStatCd} raw value — {@value #STATUS_REQUESTED} requested ·
 * {@value #STATUS_COMPLETED} completed · {@value #STATUS_WITHDRAWN} withdrawn — plus our local
 * {@value #STATUS_REJECTED} after a successful [취소 거부]. Only a later 02 / 05 from 11st overwrites
 * {@value #STATUS_REJECTED}.
 */
@Entity
@Table(name = "elevenst_cancel_request",
        uniqueConstraints = @UniqueConstraint(name = "uq_elevenst_cancel_request",
                columnNames = {"marketplace_account_id", "ord_prd_cn_seq"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ElevenstCancelRequest extends BaseEntity {

    public static final String STATUS_REQUESTED = "01";
    public static final String STATUS_COMPLETED = "02";
    public static final String STATUS_WITHDRAWN = "05";
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

    @Column(name = "ord_no", nullable = false, length = 100)
    private String ordNo;

    @Column(name = "ord_prd_seq", nullable = false, length = 50)
    private String ordPrdSeq;

    /** 11st cancel claim number ({@code ordPrdCnSeq}) — needed by approve (1640) and reject (1641). */
    @Column(name = "ord_prd_cn_seq", nullable = false, length = 100)
    private String ordPrdCnSeq;

    /** Claimed quantity ({@code ordCnQty}). */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** Who filed it ({@code ordCnMnbdCd}): 01 buyer · 02 seller. Picks the reason label of codes 06~10 (D41). */
    @Column(name = "requested_by", length = 10)
    private String requestedBy;

    @Column(name = "reason_code", length = 10)
    private String reasonCode;

    @Column(name = "reason_text", length = 1000)
    private String reasonText;

    /** {@code createDt} — KST wall clock (D32). */
    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    /** {@code cnTrtEndDt}, or the time our approve succeeded. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "platform_status", nullable = false, length = 20)
    private String platformStatus;
}
