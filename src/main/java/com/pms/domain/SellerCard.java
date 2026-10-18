package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * A payment card registered under a seller (FEATURE_2610_18 / D29 · D52 · D91 · D92).
 *
 * <p>🔴 {@link #maskedNumber} is the masked number printed on receipts, stored as typed. A full card number never
 * gets here — {@code SellerCardServiceImpl} rejects 13 or more digits in a row after removing hyphens and spaces
 * (D92 · D99 ②).</p>
 *
 * <p>Delete = {@link #hiddenAt} set (D91): a hidden card leaves the receipt popup's card list, and receipts saved
 * with it keep showing its alias. The owner seller never changes.</p>
 *
 * <p>⚠️ ddl-auto=validate (prod) → the columns below must match changeset 110-1.</p>
 */
@Entity
@Table(name = "seller_card")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SellerCard extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** The card owner — the payer seller of every receipt paid with this card (D87). Never changes (D91). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    /** Name shown in the receipt popup and the receipt list (D52 · D94). */
    @Column(name = "alias", nullable = false, length = 50)
    private String alias;

    /** Masked number as printed on receipts, e.g. {@code 1234-56**-****-3456} (D29 · D52). */
    @Column(name = "masked_number", nullable = false, length = 30)
    private String maskedNumber;

    /** Null = visible. Set by [삭제] — the row stays for the receipts that used it (D91). */
    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;
}
