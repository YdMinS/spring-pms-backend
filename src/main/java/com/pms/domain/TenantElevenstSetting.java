package com.pms.domain;

import com.pms.security.crypto.AesAttributeConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * The company's 11st photo lookup key (FEATURE_2611_11 / D39) — one row per tenant, shared by every 11st sales
 * channel of the company (the daily call limit is shared too).
 *
 * <p>The key is the 11st public OpenAPI key (「상품이미지검색」, D35), not a seller API key. 🔴 {@code photoApiKey} is
 * AES-256-GCM encrypted by {@link AesAttributeConverter} and never put in a response — the screen only learns
 * whether it is set. No row (or {@code null}) = not set → the photo read fails with the D39 guidance.</p>
 */
@Entity
@Table(name = "tenant_elevenst_setting",
        uniqueConstraints = @UniqueConstraint(name = "uq_tenant_elevenst_setting_tenant", columnNames = {"tenant_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class TenantElevenstSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Convert(converter = AesAttributeConverter.class)
    @Column(name = "photo_api_key", length = 512)
    private String photoApiKey;
}
