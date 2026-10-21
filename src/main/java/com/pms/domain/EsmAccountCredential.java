package com.pms.domain;

import com.pms.security.crypto.AesAttributeConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * ESM (Gmarket · Auction) credential (FEATURE_2610_26 / D4 · D5): the seller's own ESM+ master ID and secret key plus
 * the Gmarket and Auction seller IDs, 1:1 with {@link MarketplaceAccount}, next to {@link CoupangAccountCredential} and
 * {@link ElevenstAccountCredential}.
 *
 * <p><b>Access</b>: application code reads the key through
 * {@link com.pms.service.esm.EsmCredentials#of(MarketplaceAccount)} (platform check + 400 when unset).</p>
 *
 * <p>🔴 {@code secretKey} is AES-256-GCM encrypted by {@link AesAttributeConverter}; it is never put in a response
 * DTO. ⚠️ {@code marketplaceAccount} is UNIQUE (1:1) — update the existing row with {@code toBuilder()}.</p>
 *
 * <p>D5: both seller IDs are required and may differ (sellers who joined the two sites separately).</p>
 */
@Entity
@Table(name = "esm_account_credential",
        uniqueConstraints = @UniqueConstraint(name = "uq_esm_cred_account",
                columnNames = "marketplace_account_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class EsmAccountCredential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Hibernate auto-sets this on INSERT and auto-filters SELECTs — do NOT set it in the builder.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    /** ESM+ master ID — the token header {@code kid} (D6). */
    @Column(name = "master_id", nullable = false, length = 100)
    private String masterId;

    @Convert(converter = AesAttributeConverter.class)
    @Column(name = "secret_key", nullable = false, length = 512)
    private String secretKey;

    @Column(name = "gmarket_seller_id", nullable = false, length = 100)
    private String gmarketSellerId;

    @Column(name = "auction_seller_id", nullable = false, length = 100)
    private String auctionSellerId;
}
