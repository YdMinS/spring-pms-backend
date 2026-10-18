package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * Purchase need line (order_line × component) — the source of truth of the purchase list.
 *
 * Expanding one order line (option) by its BOM yields one row per component. A NULL order_line is a manual line.
 * Line needed quantity = autoQty + manualQty. Screens add lines up per product (and per seller, FEATURE_2610_09 D2).
 *
 * The extract is an idempotent upsert: a re-extract replaces autoQty only and keeps manualQty.
 * When cancellation, refund hold or shipping lowers the purchasable quantity, the re-extract sets that line's
 * autoQty to 0; the row stays (FEATURE_2610_09 D80 — a share that fell to 0 shows as 「필요 0」).
 *
 * Seller of a line (FEATURE_2610_09 D16): an order line reads it from order -> account -> seller and keeps
 * {@code seller} NULL; a manual line stores it in {@code seller}.
 *
 * ⚠️ ddl-auto=validate (prod) → the @Column definitions below must match the shopping_list_item DDL.
 *    UNIQUE(order_line_id, product_id). "One manual line per (product × seller)" is owned by the service layer.
 *
 * @see com.pms.domain.PurchaseRecord purchase history per product × seller
 * @see com.pms.domain.OrderLine order line (neutral core)
 */
@Entity
@Table(name = "shopping_list_item",
        uniqueConstraints = @UniqueConstraint(name = "uq_shopping_list_item",
                columnNames = {"order_line_id", "product_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ShoppingListItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 추출 출처 주문 라인. NULL = 수동 추가 라인. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id")
    private OrderLine orderLine;

    /** 실제 사입 단위(구성품). 집계/표시 기준. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Seller a manual line is bought for (FEATURE_2610_09 D11 · D16). NULL on order lines. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id")
    private Seller seller;

    /** 추출 자동값 = 발주가능수량 × 옵션당 구성수량. 수동 라인은 0. */
    @Column(name = "auto_qty", nullable = false)
    private Integer autoQty;

    /** 수동 추가/조정분. 재추출에도 보존. 기본 0. */
    @Column(name = "manual_qty", nullable = false)
    private Integer manualQty;

    /** 라인 필요수량 = autoQty + manualQty. */
    public int neededQty() {
        return autoQty + manualQty;
    }
}
