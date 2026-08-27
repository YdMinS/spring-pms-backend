package com.pms.domain;

import com.pms.security.crypto.AesAttributeConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * 외부 판매 플랫폼(쿠팡 등)의 셀러 계정 + API 자격증명.
 *
 * 관계: Seller (1) ──< MarketplaceAccount (N). 한 셀러가 여러 플랫폼·여러 계정을 보유.
 *
 * secretKey 는 {@link AesAttributeConverter} 로 AES-256-GCM 암호화되어 저장된다 (평문 보관 금지).
 * 응답 DTO 에는 secretKey 를 절대 포함하지 않는다.
 *
 * ⚠️ ddl-auto=validate(운영) → 아래 @Column 정의는 실제 marketplace_account DDL 과 일치해야 한다.
 */
@Entity
@Table(name = "marketplace_account")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MarketplaceAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    @Column(nullable = false, length = 50)
    private String platform;                 // "COUPANG"

    @Column(name = "account_alias", length = 255)
    private String accountAlias;

    @Column(name = "vendor_id", nullable = false, length = 100)
    private String vendorId;

    // WING login ID (FEATURE_2608_06 / 71). Distinct from vendorId (vendor code); required by Coupang
    // product registration. Nullable — no backfill for existing accounts, may stay unset. An identifier
    // like accessKey (not secretKey) → safe to expose in responses, no encryption converter.
    @Column(name = "vendor_user_id", length = 100)
    private String vendorUserId;

    @Column(name = "access_key", nullable = false, length = 255)
    private String accessKey;

    @Convert(converter = AesAttributeConverter.class)
    @Column(name = "secret_key", nullable = false, length = 512)
    private String secretKey;                // 평문 보관 금지 — 컨버터가 암복호화

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    // Channel template override (FEATURE_2608_06 / 21): thumbnail/detail template chosen per
    // seller×platform. Nullable — when null the asset generation resolver falls back to the tenant
    // default template ({@code findByIsDefaultTrueAndActiveTrue}). Resolved via ChannelTemplateResolver.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thumbnail_template_id", nullable = true)
    private ThumbnailTemplate thumbnailTemplate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detail_template_id", nullable = true)
    private DetailTemplate detailTemplate;

    // Channel-level override of the "옵션확인" registration-name suffix (FEATURE_2608_06 / 69). Both columns are
    // nullable — null = inherit (resolution falls through to master ?? seller ?? system). This channel override
    // wins over the master and seller levels. Resolved per field by OptionCheckSuffixResolver.
    // ⚠️ Boolean → MySQL BIT trap: changeset 037 re-types to BIT(1) on MySQL (nullable → no NOT NULL/backfill).
    @Column(name = "option_check_suffix_enabled")
    private Boolean optionCheckSuffixEnabled;

    @Column(name = "option_check_suffix", length = 50)
    private String optionCheckSuffix;
}
