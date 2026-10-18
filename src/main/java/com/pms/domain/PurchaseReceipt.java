package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDate;

/**
 * One receipt = one check-in of several products (FEATURE_2610_18 / D28 · D45 · D74 · D87).
 *
 * <p>🔴 The photo is the only record of the store, amount and card text — nothing is read from it (D74). The person
 * picks the card (→ payer seller) and the date.</p>
 *
 * <p>{@link #payerSeller} is stored even when a card is picked: editing or hiding the card later must not move a past
 * receipt to another seller (D87 · D91). {@link #card} is null when the card was unknown (cash, unregistered card).
 * After saving, only the receipt header edit changes {@link #purchasedOn}, {@link #card} and {@link #payerSeller} —
 * and with them every line's seller and purchase day (D100).</p>
 *
 * <p>The purchase lines are {@link PurchaseRecord} rows whose {@code receipt} points here, all owned by the payer
 * seller (D89). No debt is written for a receipt (D88).</p>
 *
 * <p>⚠️ ddl-auto=validate (prod) → the columns below must match changeset 110-2.</p>
 */
@Entity
@Table(name = "purchase_receipt")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PurchaseReceipt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Purchase day picked in the popup (starts at today, D74) — every line's purchasedOn. */
    @Column(name = "purchased_on", nullable = false)
    private LocalDate purchasedOn;

    /** The seller who paid = owner of every line on this receipt (D87 · D89). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_seller_id", nullable = false)
    private Seller payerSeller;

    /** The registered card picked in the popup; null = 「카드로 알 수 없음」 (D87). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id")
    private SellerCard card;

    /** Stored photo value from {@code ImageStorageService.uploadBytes} — S3 public URL or local disk path. */
    @Column(name = "photo_url", nullable = false, length = 1000)
    private String photoUrl;

    /** Username from the security context. */
    @Column(name = "created_by", length = 100)
    private String createdBy;
}
