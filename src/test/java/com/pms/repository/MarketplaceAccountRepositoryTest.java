package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
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
 * MarketplaceAccountRepository 의 seller eager-fetch 회귀 테스트.
 *
 * 송장 접수시트({@code ShippingLabelServiceImpl})는 @Transactional 없이(외부 HTTP 루프) 계정의
 * seller.sellerName 을 접근한다. open-in-view=false 환경에서 seller 가 지연로딩이면 세션이 닫혀
 * LazyInitializationException 이 나고 결과가 빈 파일로 감춰졌다. finder 의 @EntityGraph(seller)
 * 로 seller 가 조회 시점에 즉시 초기화됨을 보장한다.
 *
 * AesAttributeConverter 는 @Component(생성자 주입) 라 @DataJpaTest 슬라이스에 명시 @Import 한다
 * (secretKey 컬럼 암복호화에 필요; master-key 는 application-test.yml).
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
        MarketplaceAccount account = MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").vendorId(vendorId)
                .accessKey("ak").secretKey("sk").isActive(true).build();
        em.persist(account);
        em.flush();
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
