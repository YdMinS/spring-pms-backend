package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매상품 조회 검색(2026-09-25)의 매칭 규칙 — {@link ProductListingRepository#SEARCH_PREDICATE}.
 *
 * <p>🔴 규칙을 쿼리가 소유하므로 리포지토리를 mock 한 서비스 테스트로는 검증할 수 없다: 이름의 대소문자
 * 접기, 마켓 상품 ID 의 <b>정확</b> 일치(부분 일치면 더 긴 ID 가 통째로 딸려 온다), 그리고 검색이
 * 플랫폼·마스터연결 필터와 <b>함께</b> 걸리는지는 실제 SQL 에서만 드러난다.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
class ProductListingSearchTest {

    @Autowired private ProductListingRepository repository;
    @Autowired private TestEntityManager em;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 25);

    @BeforeEach
    void seed() {
        // tenantId 는 세팅하지 않는다 — @TenantId 가 스탬프한다(이 슬라이스에선 NO_TENANT, 자기일관적).
        Seller seller = em.persist(
                Seller.builder().sellerName("행복상회").businessRegistration("111-22-33333").build());
        MasterProduct master = em.persist(MasterProduct.builder().name("생수 마스터").active(true).build());

        cell(seller, Platform.COUPANG, "제주 삼다수 2L", "1234567", null);
        cell(seller, Platform.COUPANG, "삼다수 500ml 20개", "12345678", master);
        cell(seller, Platform.COUPANG, "BLUE 커피 원두", "7777777", null);
        cell(seller, Platform.NAVER, "제주 삼다수 2L", "9999999", null);
        em.flush();
        em.clear();
    }

    private void cell(Seller seller, Platform platform, String name, String productId, MasterProduct master) {
        em.persist(ProductListing.builder()
                .platform(platform).status(ListingStatus.SELLING).name(name)
                .seller(seller).platformProductId(productId).masterProduct(master).build());
    }

    @Test
    void search_matchesNamePartialCaseInsensitive() {
        assertThat(repository.searchByPlatform(Platform.COUPANG, "삼다수", FIRST_PAGE).getContent())
                .extracting(ProductListing::getName)
                .containsExactlyInAnyOrder("제주 삼다수 2L", "삼다수 500ml 20개");

        // 소문자 검색어가 대문자 이름을 찾는다(양방향 대소문자 접기).
        assertThat(repository.searchByPlatform(Platform.COUPANG, "blue", FIRST_PAGE).getContent())
                .extracting(ProductListing::getName).containsExactly("BLUE 커피 원두");
    }

    @Test
    void search_matchesPlatformProductIdExactlyOnly() {
        assertThat(repository.searchByPlatform(Platform.COUPANG, "1234567", FIRST_PAGE).getContent())
                .extracting(ProductListing::getPlatformProductId).containsExactly("1234567");

        // 부분 일치라면 "12345678" 까지 딸려 왔을 것이다 — 그래서 정확 일치다.
        assertThat(repository.searchByPlatform(Platform.COUPANG, "123", FIRST_PAGE).getContent()).isEmpty();
    }

    /** 검색은 플랫폼 필터를 대체하지 않는다 — 같은 이름의 네이버 셀은 쿠팡 검색에 끼어들지 않는다. */
    @Test
    void search_staysWithinPlatform() {
        Page<ProductListing> hits = repository.searchByPlatform(Platform.COUPANG, "제주", FIRST_PAGE);
        assertThat(hits.getTotalElements()).isEqualTo(1);
        assertThat(hits.getContent()).extracting(ProductListing::getPlatform).containsExactly(Platform.COUPANG);
    }

    /** 검색과 마스터 연결 필터는 AND 로 함께 걸린다(화면의 「마스터 미연결만」 체크를 켠 채 검색). */
    @Test
    void search_combinesWithMasterLinkFilter() {
        assertThat(repository.searchByPlatformAndMasterProductIsNull(Platform.COUPANG, "삼다수", FIRST_PAGE)
                        .getContent())
                .extracting(ProductListing::getName).containsExactly("제주 삼다수 2L");

        assertThat(repository.searchByPlatformAndMasterProductIsNotNull(Platform.COUPANG, "삼다수", FIRST_PAGE)
                        .getContent())
                .extracting(ProductListing::getName).containsExactly("삼다수 500ml 20개");
    }

    /** 페이지네이션이 검색 결과 <b>전체</b>를 세는지 — count 쿼리가 where 절을 같이 쓰는지 확인한다. */
    @Test
    void search_countsAllMatchesNotJustThePage() {
        Page<ProductListing> firstOfTwo = repository.searchByPlatform(
                Platform.COUPANG, "삼다수", PageRequest.of(0, 1));
        assertThat(firstOfTwo.getContent()).hasSize(1);
        assertThat(firstOfTwo.getTotalElements()).isEqualTo(2);
        assertThat(firstOfTwo.getTotalPages()).isEqualTo(2);
    }
}
