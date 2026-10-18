package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One processing record of a (product × seller) share (FEATURE_2610_09 / D59 · D62 · D85).
 *
 * <p>🔴 Rows are never deleted. Undo ([완료 취소] · [되돌리기]) and "the need came back" (D85) set
 * {@link #cancelledAt}. At most one row per share and per kind (screen-made / {@link PurchaseCheckStatus#NEED_ZERO})
 * stays uncancelled — {@code PurchaseListServiceImpl} cancels the previous one before it saves a new one.</p>
 *
 * <p>{@link #neededQty} = the share's needed quantity when the record was made. A PURCHASED / NOT_NEEDED record
 * stops counting once the current needed quantity is larger (D60 · D66 · D82).</p>
 *
 * <p>⚠️ ddl-auto=validate (prod) → the columns below must match changeset 109-3.</p>
 */
@Entity
@Table(name = "purchase_check")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PurchaseCheck extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PurchaseCheckStatus status;

    /** KST calendar day the record was made — the completed tab's date (D63 · D85). */
    @Column(name = "checked_on", nullable = false)
    private LocalDate checkedOn;

    /** The share's needed quantity at that moment (D60 · D62). */
    @Column(name = "needed_qty", nullable = false)
    private Integer neededQty;

    /** Null = in effect. Set by undo or by the extract (D62 · D85). */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** Username from the security context; null for the extract's NEED_ZERO rows written outside a request. */
    @Column(name = "created_by", length = 100)
    private String createdBy;
}
