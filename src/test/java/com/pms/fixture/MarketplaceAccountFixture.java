package com.pms.fixture;

import com.pms.domain.CoupangAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.fixture.MarketplaceAccountFixture;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 쿠팡 계정 + 자격증명 시드 (FEATURE_2609_26 / PLAN D15).
 *
 * <p><b>필수 규칙</b>: 테스트에서 {@code MarketplaceAccount.builder()} 를 직접 부르지 말고 여기를 통과한다.
 * 자격증명은 별도 엔티티({@link CoupangAccountCredential})라 계정만 만들면 쿠팡 경로가
 * {@code CoupangCredentials.of(account)} 에서 400 으로 떨어진다.
 *
 * <p><b>사용 예</b>:
 * <pre>
 * // 순수 단위테스트(Mockito) — 영속화 없음
 * MarketplaceAccount account = MarketplaceAccountFixture.coupangAccountStub();
 *
 * // 필드를 더 얹고 싶을 때 (자격증명은 이미 붙어 있다)
 * MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A00012345", "wing-user")
 *         .id(7L).seller(seller).accountAlias("쿠팡-메인").build();
 *
 * // @DataJpaTest — 계정과 자격증명을 둘 다 영속화한다
 * MarketplaceAccount account = MarketplaceAccountFixture.coupangAccount(em, seller);
 * </pre>
 *
 * <p>⚠️ {@code coupangCredential} 은 읽기 전용 역방향(mappedBy)이라 <b>영속화 경로에서는</b> 빌더로 넣어도
 * 저장되지 않는다 — {@link #coupangAccount} 처럼 자격증명 쪽을 따로 persist 해야 한다.
 * 스텁 경로에서만 빌더로 미리 붙여 쓴다.
 * ⚠️ {@code tenantId} 는 절대 세팅하지 말 것 — Hibernate 가 INSERT 시 스탬프한다.
 */
public final class MarketplaceAccountFixture {

    public static final String VENDOR_ID = "V1";
    public static final String ACCESS_KEY = "ak";
    public static final String SECRET_KEY = "sk";

    private MarketplaceAccountFixture() {
    }

    /** 영속화 없이 계정만 필요할 때(순수 단위테스트 · Mockito). 자격증명이 붙어 있다. */
    public static MarketplaceAccount coupangAccountStub() {
        return coupangStubBuilder().build();
    }

    /** 자격증명이 붙은 활성 쿠팡 계정 빌더 — 필요한 필드를 더 얹고 {@code build()} 한다. */
    public static MarketplaceAccount.MarketplaceAccountBuilder coupangStubBuilder() {
        return coupangStubBuilder(VENDOR_ID, null);
    }

    /** vendorId·vendorUserId 를 지정하는 스텁 빌더(경로·바디 단언용). */
    public static MarketplaceAccount.MarketplaceAccountBuilder coupangStubBuilder(String vendorId,
                                                                                  String vendorUserId) {
        return coupangStubBuilder(vendorId, vendorUserId, ACCESS_KEY, SECRET_KEY);
    }

    /** HMAC 서명값까지 지정하는 스텁 빌더(CoupangApiClient 서명 검증용). */
    public static MarketplaceAccount.MarketplaceAccountBuilder coupangStubBuilder(String vendorId,
                                                                                  String vendorUserId,
                                                                                  String accessKey,
                                                                                  String secretKey) {
        return MarketplaceAccount.builder()
                .platform(Platform.COUPANG)
                .isActive(true)
                .coupangCredential(credential(vendorId, vendorUserId, accessKey, secretKey));
    }

    /**
     * 🔴 <b>영속화 경로용</b> 계정 빌더 — 자격증명을 붙이지 않는다.
     *
     * <p>{@code coupangCredential} 은 읽기 전용 역방향인데도 Hibernate 는 flush 시 그 대상이 영속인지
     * 검사한다 → 스텁 자격증명이 붙은 계정을 저장하면 {@code TransientPropertyValueException} 이 난다.
     * 저장한 뒤 자격증명이 필요하면 {@link #saveCredential} 로 따로 붙인다.
     */
    public static MarketplaceAccount.MarketplaceAccountBuilder coupangCoreBuilder() {
        return MarketplaceAccount.builder().platform(Platform.COUPANG).isActive(true);
    }

    /**
     * 이미 저장된 계정에 자격증명 행을 붙인다(JpaRepository 경로).
     *
     * <p>⚠️ 저장 후 계정 인스턴스의 역방향 필드를 함께 채운다 — {@code mappedBy} 라 같은 영속성 컨텍스트가
     * 돌려주는 계정(1차 캐시 동일 인스턴스)에는 자동 반영되지 않아, 안 채우면 서비스가
     * "자격증명 미설정" 400 을 본다.
     */
    public static CoupangAccountCredential saveCredential(
            CoupangAccountCredentialRepository repository, MarketplaceAccount account,
            String vendorId, String vendorUserId) {
        CoupangAccountCredential saved = repository.save(CoupangAccountCredential.builder()
                .marketplaceAccount(account)
                .vendorId(vendorId)
                .vendorUserId(vendorUserId)
                .accessKey(ACCESS_KEY)
                .secretKey(SECRET_KEY)
                .build());
        ReflectionTestUtils.setField(account, "coupangCredential", saved);
        return saved;
    }

    /** 자격증명이 <b>없는</b> 계정(미설정 방어 경로 검증용). */
    public static MarketplaceAccount accountWithoutCredential(Platform platform) {
        return MarketplaceAccount.builder().platform(platform).isActive(true).build();
    }

    /** 스텁용 자격증명(계정 역참조 없음 — {@code CoupangCredentials.of} 는 계정에서 읽기만 한다). */
    public static CoupangAccountCredential credential(String vendorId, String vendorUserId) {
        return credential(vendorId, vendorUserId, ACCESS_KEY, SECRET_KEY);
    }

    /** 스텁용 자격증명(HMAC 키 지정). */
    public static CoupangAccountCredential credential(String vendorId, String vendorUserId,
                                                      String accessKey, String secretKey) {
        return CoupangAccountCredential.builder()
                .vendorId(vendorId)
                .vendorUserId(vendorUserId)
                .accessKey(accessKey)
                .secretKey(secretKey)
                .build();
    }

    /** @DataJpaTest: 활성 쿠팡 계정 + 자격증명을 영속화한다(flush 까지, clear 는 호출자 몫). */
    public static MarketplaceAccount coupangAccount(TestEntityManager em, Seller seller) {
        return coupangAccount(em, seller, VENDOR_ID);
    }

    /** @DataJpaTest: vendorId 를 지정해 계정 + 자격증명을 영속화한다. */
    public static MarketplaceAccount coupangAccount(TestEntityManager em, Seller seller, String vendorId) {
        MarketplaceAccount account = MarketplaceAccount.builder()
                .seller(seller)
                .platform(Platform.COUPANG)
                .isActive(true)
                .build();
        em.persist(account);
        em.persist(CoupangAccountCredential.builder()
                .marketplaceAccount(account)
                .vendorId(vendorId)
                .accessKey(ACCESS_KEY)
                .secretKey(SECRET_KEY)
                .build());
        em.flush();
        return account;
    }
}
