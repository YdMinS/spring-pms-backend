package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * 11st order line extension — the 11st mirror of one {@link OrderLine} (FEATURE_2610_17 / D5, changeset 108).
 *
 * <p>🔴 The 11st natural key lives here: UNIQUE(marketplace_account_id, ord_no, ord_prd_seq). The neutral core gets
 * no 11st column — one table with both markets' keys would leave NULL key columns, and MySQL allows NULL duplicates
 * in a UNIQUE (2609_26 D2). Same shape as {@link CoupangOrderLine}.
 *
 * <p>Written only through {@code ElevenstOrderUpserter} (mirror). {@code raw} holds the allow-listed fields of the 11st order row only
 * (D43) — no address, phone, request text or member id.
 *
 * <p>⚠️ ddl-auto=validate → {@code raw} is a CLOB column mapped as LONGVARCHAR (same as {@link CoupangOrderLine}).
 */
@Entity
@Table(name = "elevenst_order_line",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_elevenst_order_line",
                        columnNames = {"marketplace_account_id", "ord_no", "ord_prd_seq"}),
                @UniqueConstraint(name = "uq_elevenst_order_line_line", columnNames = "order_line_id")
        })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ElevenstOrderLine extends BaseEntity {

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

    /** 11st order number ({@code ordNo}) — the same value as {@code orders.external_order_id}. */
    @Column(name = "ord_no", nullable = false, length = 100)
    private String ordNo;

    /** 11st order line sequence ({@code ordPrdSeq}). */
    @Column(name = "ord_prd_seq", nullable = false, length = 50)
    private String ordPrdSeq;

    /** 11st product number ({@code prdNo}). */
    @Column(name = "prd_no", length = 100)
    private String prdNo;

    /** 11st option code of the ordered item ({@code prdStckNo}) — the response {@code externalItemId}. */
    @Column(name = "prd_stck_no", length = 100)
    private String prdStckNo;

    /** Seller stock code ({@code sellerStockCd}) = our listing option id for listings we registered (D17). */
    @Column(name = "seller_stock_cd", length = 100)
    private String sellerStockCd;

    /** Raw 11st status = the code of the list the row came from (202 · 301 · 401 · 501, D6). */
    @Column(name = "platform_status", length = 30)
    private String platformStatus;

    /** Allow-listed order row fields as XML (D43). Overwritten on every sync (mirror). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw")
    private String raw;
}
