package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Coupang settlement line extension — the mirror of {@link SettlementLine} (PLAN 2609_30 D6).
 *
 * <p>Explicit columns AND {@code raw} together, never {@code raw} alone: reconciliation's whole value
 * is answering "<b>which item</b> ate the 30,000 won", and a JSON blob cannot be aggregated per item.
 * The precedent is {@link CoupangOrderLine} (raw + explicit columns).</p>
 *
 * <p>1:1 with the neutral line — a second mirror row for one line is a bug, not a variant.</p>
 *
 * <p>⚠️ {@code raw} is a MySQL TEXT column (JDBC LONGVARCHAR). {@code @Lob} expects a CLOB and fails
 * ddl-auto=validate, so it is mapped with {@code @JdbcTypeCode(LONGVARCHAR)} exactly like
 * {@link CoupangOrderLine#getRaw()}.</p>
 *
 * <p>⚠️ {@code taxType} is what the dev verification step groups {@code serviceFeeVat / serviceFee} by:
 * if tax-exempt items make the ratio split, prompt 07's VAT-rate assumption (D19) has to be revisited.</p>
 */
@Entity
@Table(name = "coupang_settlement_line",
        uniqueConstraints = @UniqueConstraint(name = "uq_coupang_settlement_line",
                columnNames = "settlement_line_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CoupangSettlementLine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_line_id", nullable = false)
    private SettlementLine settlementLine;

    /** Lets the mirror be filtered without joining through the neutral line (same stance as CoupangOrderLine). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    @Column(name = "order_id_raw", nullable = false, length = 100)
    private String orderIdRaw;

    /** Coupang vendorItemId. */
    @Column(name = "platform_option_id", nullable = false, length = 100)
    private String platformOptionId;

    /** Coupang productId (sellerProductId is a different value — do not conflate). */
    @Column(name = "platform_product_id", length = 100)
    private String platformProductId;

    @Column(name = "product_name", length = 500)
    private String productName;

    /** Raw taxType (TAX / FREE ...). Read by the dev verification of the fee-VAT ratio. */
    @Column(name = "tax_type", length = 20)
    private String taxType;

    @Column(name = "courantee_fee", precision = 15, scale = 2)
    private BigDecimal couranteeFee;

    @Column(name = "courantee_fee_vat", precision = 15, scale = 2)
    private BigDecimal couranteeFeeVat;

    @Column(name = "store_fee_discount", precision = 15, scale = 2)
    private BigDecimal storeFeeDiscount;

    @Column(name = "downloadable_coupon", precision = 15, scale = 2)
    private BigDecimal downloadableCoupon;

    @Column(name = "seller_discount_coupon", precision = 15, scale = 2)
    private BigDecimal sellerDiscountCoupon;

    @Column(name = "coupang_discount_coupon", precision = 15, scale = 2)
    private BigDecimal coupangDiscountCoupon;

    @Column(name = "external_seller_sku_code", length = 100)
    private String externalSellerSkuCode;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Column(name = "final_settlement_date")
    private LocalDate finalSettlementDate;

    /** One raw response line. Overwritten on every load (it is a mirror). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw")
    private String raw;
}
