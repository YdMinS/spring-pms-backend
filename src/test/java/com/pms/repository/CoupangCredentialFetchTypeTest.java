package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.CoupangAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.security.crypto.AesAttributeConverter;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 EAGER 회귀 가드 (FEATURE_2609_26 / 02).
 *
 * <p>자격증명은 52곳에서 읽히는데 그중 여럿이 트랜잭션 밖이다(`open-in-view: false` +
 * `ShipmentConfirmServiceImpl`·`OrderAcknowledgeServiceImpl` 은 클래스에 @Transactional 이 없다).
 * `MarketplaceAccount.coupangCredential` 이 LAZY 로 되돌아가면 그 경로가 런타임에 터진다.
 *
 * <p>⚠️ @DataJpaTest 는 기본 @Transactional 이라 그냥 게터를 읽으면 LAZY 로 되돌려도 통과한다(거짓 GREEN)
 * → 세션을 비우고 {@link Hibernate#isInitialized} 로 단언한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class CoupangCredentialFetchTypeTest {

    @Autowired
    private MarketplaceAccountRepository accountRepository;

    @Autowired
    private CoupangAccountCredentialRepository credentialRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findById_eagerlyLoadsCoupangCredential() {
        Seller seller = Seller.builder().sellerName("셀러A").businessRegistration("123-45-67890").build();
        em.persist(seller);
        Long accountId = MarketplaceAccountFixture.coupangAccount(em, seller, "A00012345").getId();
        em.clear();                                     // detach everything first

        MarketplaceAccount account = accountRepository.findById(accountId).orElseThrow();

        assertThat(Hibernate.isInitialized(account.getCoupangCredential())).isTrue();   // fetch-type guard
        assertThat(account.getCoupangCredential().getVendorId()).isEqualTo("A00012345");
    }

    @Test
    void secretKeyRoundTripsThroughTheEncryptionConverter() {
        Seller seller = Seller.builder().sellerName("셀러B").businessRegistration("999-88-77777").build();
        em.persist(seller);
        Long accountId = MarketplaceAccountFixture.coupangAccount(em, seller, "B00012345").getId();
        em.clear();

        CoupangAccountCredential credential = credentialRepository
                .findByMarketplaceAccountId(accountId).orElseThrow();

        // 컨버터가 빠지면 여기서 암호문이 나오고, HMAC 서명이 전부 깨진다(증상은 쿠팡 401 뿐).
        assertThat(credential.getSecretKey()).isEqualTo(MarketplaceAccountFixture.SECRET_KEY);
    }
}
