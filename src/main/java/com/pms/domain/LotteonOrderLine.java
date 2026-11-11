package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * LotteON order line extension — the LotteON mirror of one {@link OrderLine} (FEATURE_2611_23 / D6, changeset 132).
 *
 * <p>🔴 The LotteON natural key lives here: UNIQUE(marketplace_account_id, od_no, od_seq). The neutral core gets no
 * LotteON column (D6). Same shape as {@link ElevenstOrderLine}.
 *
 * <p>Written only through {@code LotteonOrderUpserter} (mirror). {@code raw} holds the allow-listed fields of the
 * LotteON order row only (D9) — no address, contact or delivery message.
 *
 * <p>⚠️ ddl-auto=validate → {@code raw} is a CLOB column mapped as LONGVARCHAR (same as {@link ElevenstOrderLine}).
 */
@Entity
@Table(name = "lotteon_order_line",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_lotteon_order_line",
                        columnNames = {"marketplace_account_id", "od_no", "od_seq"}),
                @UniqueConstraint(name = "uq_lotteon_order_line_line", columnNames = "order_line_id")
        })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LotteonOrderLine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (2609_26 D25). Hibernate sets it on INSERT and filters SELECTs — never set it by hand.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine orderLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    /** LotteON order number ({@code odNo}) — the same value as {@code orders.external_order_id} (D7). */
    @Column(name = "od_no", nullable = false, length = 50)
    private String odNo;

    /** LotteON order line sequence ({@code odSeq}). */
    @Column(name = "od_seq", nullable = false, length = 10)
    private String odSeq;

    /** LotteON process sequence ({@code procSeq}) — the last value read (D6). */
    @Column(name = "proc_seq", length = 10)
    private String procSeq;

    /** Raw LotteON status ({@code odPrgsStepCd}, D6) — moves forward only (R5). */
    @Column(name = "platform_status", length = 10)
    private String platformStatus;

    /** LotteON product number ({@code spdNo}). */
    @Column(name = "spd_no", length = 50)
    private String spdNo;

    /** LotteON item number ({@code sitmNo}) — the response {@code externalItemId} (R12). */
    @Column(name = "sitm_no", length = 50)
    private String sitmNo;

    /** Seller item number ({@code eitmNo}) = our listing option id for listings we registered (D12). */
    @Column(name = "eitm_no", length = 50)
    private String eitmNo;

    /** Combined delivery group number ({@code cmbnDvGrpNo}, D7). */
    @Column(name = "cmbn_dv_grp_no", length = 20)
    private String cmbnDvGrpNo;

    /** Allow-listed order row fields as JSON (D9 · R8). Overwritten on every sync (mirror). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw")
    private String raw;
}
