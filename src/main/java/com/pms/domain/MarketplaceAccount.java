package com.pms.domain;

import com.pms.security.crypto.AesAttributeConverter;
import jakarta.persistence.*;
import lombok.*;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Seller seller;

    @Column(nullable = false, length = 50)
    private String platform;                 // "COUPANG"

    @Column(name = "account_alias", length = 255)
    private String accountAlias;

    @Column(name = "vendor_id", nullable = false, length = 100)
    private String vendorId;

    @Column(name = "access_key", nullable = false, length = 255)
    private String accessKey;

    @Convert(converter = AesAttributeConverter.class)
    @Column(name = "secret_key", nullable = false, length = 512)
    private String secretKey;                // 평문 보관 금지 — 컨버터가 암복호화

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
