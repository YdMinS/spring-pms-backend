package com.pms.domain;

import com.pms.security.crypto.AesAttributeConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * 쿠팡 OpenAPI 호출용 자격증명 (FEATURE_2609_26 / PLAN D15).
 *
 * <p>{@link MarketplaceAccount} 는 중립 core(판매자·플랫폼·별칭·동기화 상태)만 들고, 쿠팡 HMAC 4필드는
 * 여기로 내려온다. 네이버는 {@code client_id}/{@code client_secret} + <b>만료되는 OAuth 토큰</b>이라
 * core 에 nullable 컬럼을 덧붙이는 방식으로는 표현할 수 없다 — 플랫폼별 테이블이 필수·만료 제약을 각자 보장한다.
 *
 * <p><b>접근 경로</b>: 애플리케이션 코드는 이 엔티티를 직접 꺼내지 말고
 * {@link com.pms.service.coupang.CoupangCredentials#of(MarketplaceAccount)} 를 통과한다
 * (플랫폼 검증 + 미설정 시 400).
 *
 * <p>🔴 {@code secretKey} 는 {@link AesAttributeConverter} 로 AES-256-GCM 암호화되어 저장된다.
 * 컨버터가 빠지면 DB 암호문이 그대로 서명 키로 쓰여 <b>HMAC 이 전부 깨진다</b>(증상은 쿠팡 401 뿐).
 * 컬럼 길이 512 도 암호문 길이 때문이다. 응답 DTO 에는 절대 포함하지 않는다.
 *
 * <p>⚠️ {@code marketplaceAccount} 는 UNIQUE(1:1). 수정은 반드시 기존 행을
 * {@code toBuilder()} 로 갱신한다 — 새 인스턴스를 저장하면 {@code uq_coupang_cred_account} 위반이다.
 *
 * <p>⚠️ ddl-auto=validate(운영) → 아래 @Column 정의는 실제 coupang_account_credential DDL(changeset 065)과
 * 일치해야 한다.
 */
@Entity
@Table(name = "coupang_account_credential",
        uniqueConstraints = @UniqueConstraint(name = "uq_coupang_cred_account",
                columnNames = "marketplace_account_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CoupangAccountCredential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (PLAN D25). Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT set it in the builder (assigned != current raises) and do NOT add manual tenant conditions.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    /** 쿠팡 vendor code (예 A00012345). HMAC 경로 치환에 쓰인다. */
    @Column(name = "vendor_id", nullable = false, length = 100)
    private String vendorId;

    // WING login ID (FEATURE_2608_06 / 71). Distinct from vendorId (vendor code); required by Coupang
    // product registration. Nullable — no backfill for existing accounts, may stay unset. An identifier
    // like accessKey (not secretKey) → safe to expose in responses, no encryption converter.
    @Column(name = "vendor_user_id", length = 100)
    private String vendorUserId;

    @Column(name = "access_key", nullable = false, length = 255)
    private String accessKey;

    // 🔴 평문 보관 금지 — 이 컨버터가 빠지면 암호문을 서명 키로 쓰게 되어 모든 쿠팡 호출이 401 이 된다.
    @Convert(converter = AesAttributeConverter.class)
    @Column(name = "secret_key", nullable = false, length = 512)
    private String secretKey;
}
