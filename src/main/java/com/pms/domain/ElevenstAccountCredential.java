package com.pms.domain;

import com.pms.security.crypto.AesAttributeConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * 11st Open API credential (FEATURE_2610_13 / D2 · D5): the seller's own API key, 1:1 with
 * {@link MarketplaceAccount}, next to {@link CoupangAccountCredential}.
 *
 * <p><b>Access</b>: application code reads the key through
 * {@link com.pms.service.elevenst.ElevenstCredentials#of(MarketplaceAccount)} (platform check + 400 when unset).</p>
 *
 * <p>🔴 {@code apiKey} is AES-256-GCM encrypted by {@link AesAttributeConverter}; it is never put in a response
 * DTO. ⚠️ {@code marketplaceAccount} is UNIQUE (1:1) — update the existing row with {@code toBuilder()}.</p>
 *
 * <p>{@code keyLastUsedAt} = the last successful keyed 11st call (D88). 11st stops a key that has not been used
 * for 180 days, so the account screens warn after 150 days.</p>
 */
@Entity
@Table(name = "elevenst_account_credential",
        uniqueConstraints = @UniqueConstraint(name = "uq_elevenst_cred_account",
                columnNames = "marketplace_account_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ElevenstAccountCredential extends BaseEntity {

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

    @Convert(converter = AesAttributeConverter.class)
    @Column(name = "api_key", nullable = false, length = 512)
    private String apiKey;

    @Column(name = "key_last_used_at")
    private LocalDateTime keyLastUsedAt;
}
