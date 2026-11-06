package com.pms.domain;

import com.pms.security.crypto.AesAttributeConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * LotteON credential (FEATURE_2610_24 / D4 · D5 · D6): the seller's own 「인증키」, the seller group code and seller
 * number LotteON returned for it (Identity, D5) and the time the key was saved (D6), 1:1 with
 * {@link MarketplaceAccount}, next to {@link CoupangAccountCredential}, {@link ElevenstAccountCredential} and
 * {@link EsmAccountCredential}.
 *
 * <p><b>Access</b>: application code reads the key through
 * {@link com.pms.service.lotteon.LotteonCredentials#of(MarketplaceAccount)} (platform check + 400 when unset).</p>
 *
 * <p>🔴 {@code apiKey} is AES-256-GCM encrypted by {@link AesAttributeConverter}; it is never put in a response DTO.
 * ⚠️ {@code marketplaceAccount} is UNIQUE (1:1) — update the existing row with {@code toBuilder()}.</p>
 *
 * <p>D5: {@code trGrpCd} · {@code trNo} stay null on a server that does not call LotteON (D8).</p>
 */
@Entity
@Table(name = "lotteon_account_credential",
        uniqueConstraints = @UniqueConstraint(name = "uq_lotteon_cred_account",
                columnNames = "marketplace_account_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LotteonAccountCredential extends BaseEntity {

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

    /** D5: Identity {@code trGrpCd} (seller group code). */
    @Column(name = "tr_grp_cd", length = 50)
    private String trGrpCd;

    /** D5: Identity {@code trNo} (seller number). */
    @Column(name = "tr_no", length = 200)
    private String trNo;

    /** D6: when the key was entered or replaced — the renewal warning counts from here. */
    @Column(name = "key_saved_at")
    private LocalDateTime keySavedAt;
}
