package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.ListingStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductListingRepository#findRepricingTargets} 의 제외 규칙(FEATURE_2609_39 / PLAN D17·D20).
 *
 * <p>🔴 이 규칙은 쿼리가 소유하므로 리포지토리를 mock 한 서비스 테스트로는 검증할 수 없다. 특히
 * {@code SUSPENDED}·{@code REJECTED} 셀은 <b>마켓 식별자가 그대로 남아 있어</b> 걸러지지 않으면 목록에 뜨고
 * 전송까지 간다 — 팔지 않는 상품의 가격을 바꾸는 것은 기능이 아니라 사고다.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class ProductListingRepricingTargetTest {

    @Autowired private ProductListingRepository repository;
    @Autowired private TestEntityManager em;

    private Long sellerId;
    private Long otherSellerId;

    @BeforeEach
    void seed() {
        // tenantId 는 세팅하지 않는다 — @TenantId 가 스탬프한다(이 슬라이스에선 NO_TENANT, 자기일관적).
        Seller seller = em.persist(Seller.builder().sellerName("행복상회").businessRegistration("111-22-33333").build());
        Seller other = em.persist(Seller.builder().sellerName("다른상회").businessRegistration("222-33-44444").build());
        sellerId = seller.getId();
        otherSellerId = other.getId();

        cell(seller, Platform.COUPANG, ListingStatus.SELLING, "판매중");
        cell(seller, Platform.COUPANG, ListingStatus.DRAFT, "임시");
        cell(seller, Platform.COUPANG, ListingStatus.SUBMITTED, "심사중");
        cell(seller, Platform.COUPANG, ListingStatus.SUSPENDED, "판매중지");
        cell(seller, Platform.COUPANG, ListingStatus.REJECTED, "반려");
        cell(seller, Platform.NAVER, ListingStatus.SELLING, "네이버 판매중");
        cell(other, Platform.COUPANG, ListingStatus.SELLING, "다른 판매자");
        em.flush();
        em.clear();
    }

    private void cell(Seller seller, Platform platform, ListingStatus status, String name) {
        em.persist(ProductListing.builder()
                .platform(platform).status(status).name(name).seller(seller)
                .platformProductId("SP-" + name).build());
    }

    @Test
    void findRepricingTargets_keepsOnlySellingCoupangCells() {
        assertThat(repository.findRepricingTargets(null, null))
                .extracting(ProductListing::getName)
                .containsExactlyInAnyOrder("판매중", "다른 판매자");
    }

    @Test
    void findRepricingTargets_filtersBySellerAndPlatform() {
        assertThat(repository.findRepricingTargets(sellerId, Platform.COUPANG))
                .extracting(ProductListing::getName).containsExactly("판매중");
        assertThat(repository.findRepricingTargets(otherSellerId, null))
                .extracting(ProductListing::getName).containsExactly("다른 판매자");
        // 쿠팡 외 플랫폼을 지정하면 결과가 없다 — 가격을 밀 어댑터가 없기 때문이다(D17).
        assertThat(repository.findRepricingTargets(sellerId, Platform.NAVER)).isEmpty();
    }
}
