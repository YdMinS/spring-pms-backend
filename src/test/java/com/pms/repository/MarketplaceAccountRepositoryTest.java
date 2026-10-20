package com.pms.repository;

import com.pms.common.TestJpaConfig;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test of MarketplaceAccountRepository's seller eager fetch.
 *
 * The label sheet's row readers ({@code CoupangShippingLabelAdapter} · {@code ElevenstShippingLabelAdapter}) read the
 * account's seller.sellerName without @Transactional (an external HTTP loop). With open-in-view=false a lazy seller
 * met a closed session, threw LazyInitializationException and the result hid behind an empty file. The finder's
 * @EntityGraph(seller) makes sure the seller is initialized when it is read.
 *
 * AesAttributeConverter is a @Component (constructor injection), so the @DataJpaTest slice @Imports it explicitly
 * (needed to encrypt / decrypt the secretKey column; the master key is in application-test.yml).
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class MarketplaceAccountRepositoryTest {

    @Autowired
    private MarketplaceAccountRepository accountRepository;

    @Autowired
    private TestEntityManager em;

    /** 활성 COUPANG 계정 1건을 저장하고 영속성 컨텍스트를 비운다(지연로딩 프록시 상황 재현). 반환: sellerId. */
    private Long persistActiveCoupangAccount(String sellerName, String bizReg, String vendorId) {
        Seller seller = Seller.builder()
                .sellerName(sellerName).businessRegistration(bizReg).build();
        em.persist(seller);
        MarketplaceAccountFixture.coupangAccount(em, seller, vendorId);
        em.clear();
        return seller.getId();
    }

    @Test
    void findByIsActiveTrue_eagerlyFetchesSeller() {
        persistActiveCoupangAccount("셀러A", "123-45-67890", "A00012345");

        List<MarketplaceAccount> accounts = accountRepository.findByIsActiveTrue();

        assertThat(accounts).hasSize(1);
        // @EntityGraph 로 seller 가 즉시 초기화되어야 함 — 프록시(미초기화)면 회귀.
        assertThat(Hibernate.isInitialized(accounts.get(0).getSeller())).isTrue();
        assertThat(accounts.get(0).getSeller().getSellerName()).isEqualTo("셀러A");
    }

    @Test
    void findBySellerIdAndIsActiveTrue_eagerlyFetchesSeller() {
        Long sellerId = persistActiveCoupangAccount("셀러B", "999-88-77777", "B00012345");

        List<MarketplaceAccount> accounts = accountRepository.findBySeller_IdAndIsActiveTrue(sellerId);

        assertThat(accounts).hasSize(1);
        assertThat(Hibernate.isInitialized(accounts.get(0).getSeller())).isTrue();
    }
}
