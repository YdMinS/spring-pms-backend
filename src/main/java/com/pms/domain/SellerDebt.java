package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One borrowed share of an outbound row — 「출고 차용」 (FEATURE_2610_19 D33 · D34 · D51).
 *
 * <p>🔴 A calculation result, never typed by a person (D111): {@code LotLedgerService.rebuildDebts} deletes and
 * rewrites every row of a product whenever an owner or a unit price behind it changes (D132 · D141 ④). Cash
 * settlements live in {@link SellerSettlement} and are never touched by that rewrite.
 *
 * <p>{@code unitPrice} / {@code amount} null = 「미기입」 (D137): the lot had no price and the lender had no priced lot
 * left (D136). It is never written as 0 and is left out of the pair balance.
 *
 * <p>Not a {@link BaseEntity}: it is rewritten, so "who made this row and when" has no meaning.
 */
@Entity
@Table(name = "seller_debt")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SellerDebt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** The order seller — the one who used someone else's goods. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "borrower_seller_id", nullable = false)
    private Seller borrower;

    /** The owner of the goods that left. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lender_seller_id", nullable = false)
    private Seller lender;

    /** The outbound row ({@code STOCK_OUT}) that used the goods. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "out_movement_id", nullable = false)
    private StockMovement outMovement;

    /** The lot the goods came from (the outbound row's source). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_movement_id", nullable = false)
    private StockMovement sourceMovement;

    /** The assigned purchase (share) of an undecided lot that was used (D131); null for an ordinary lot. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_record_id")
    private PurchaseRecord shareRecord;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    /** The outbound row's moved day. */
    @Column(name = "moved_on", nullable = false)
    private LocalDate movedOn;
}
