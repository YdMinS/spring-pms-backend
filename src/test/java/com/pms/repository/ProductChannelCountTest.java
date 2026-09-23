package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.ListingStatus;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.dto.response.ProductUsageResponse;
import com.pms.service.ProductUsageService;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductUsageService#countChannelsByProduct} against a real DB (2026-09-23).
 *
 * <p>물품 목록의 「판매채널」 컬럼이 「연결 현황」 화면과 <b>같은 숫자</b>를 말해야 한다. 그 동치는 쿼리가
 * 소유하므로(두 경로의 합집합 · listing id distinct · 테넌트 필터) mock 서비스 테스트로는 증명되지 않는다 —
 * 그래서 실제 SQL 로 돌리고, 같은 데이터에 대해 {@code getUsage} 가 세는 채널 수와 맞춰 본다.</p>
 *
 * <p>{@code ProductUsageService} 는 리포지토리만 의존하므로 슬라이스에 그대로 {@code @Import} 한다.</p>
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import({TestJpaConfig.class, ProductUsageService.class})
class ProductChannelCountTest {

    @Autowired private ProductUsageService service;
    @Autowired private TestEntityManager em;

    private Long twoMasters;
    private Long noChannel;
    private Long noMaster;

    @BeforeEach
    void seed() {
        // tenantId 는 세팅하지 않는다 — @TenantId 가 스탬프한다(이 슬라이스에선 NO_TENANT, 자기일관적).
        Seller seller = em.persist(
                Seller.builder().sellerName("행복상회").businessRegistration("111-22-33333").build());

        Product water = product("생수 2L");
        Product cup = product("종이컵");
        Product lonely = product("연결 없는 물품");
        twoMasters = water.getId();
        noChannel = cup.getId();
        noMaster = lonely.getId();

        // 마스터 A: 셀 2개. 같은 물품이 구성상품 경로와 옵션 items 경로 양쪽에 걸린다 → 중복 계산 금지.
        MasterProduct masterA = master("생수 묶음", water);
        cell(masterA, seller, "생수 6개입");
        cell(masterA, seller, "생수 12개입");

        // 마스터 B: 셀 1개. 같은 물품이 마스터 두 곳에 걸쳐 있다 → 합계 3.
        MasterProduct masterB = master("생수 + 컵", water);
        cell(masterB, seller, "생수와 컵");

        // 마스터 C: 셀 없음 → 그 물품은 채널 0.
        master("컵 단품", cup);

        em.flush();
        em.clear();
    }

    /** 한 마스터에 셀이 여럿이어도, 두 경로에 같은 물품이 걸려 있어도 셀은 한 번씩만 센다. */
    @Test
    void countsDistinctCellsAcrossMasters() {
        assertThat(service.countChannelsByProduct(List.of(twoMasters))).containsEntry(twoMasters, 3);
    }

    /** 마스터에 붙었지만 채널이 없는 물품 · 마스터에도 안 붙은 물품은 키 자체가 없다(호출부가 0 으로 읽는다). */
    @Test
    void productsWithoutChannelsAreAbsent() {
        Map<Long, Integer> counts = service.countChannelsByProduct(List.of(noChannel, noMaster));

        assertThat(counts).doesNotContainKeys(noChannel, noMaster);
    }

    /**
     * 🔴 이 기능의 존재 이유 — 목록의 숫자가 「연결 현황」이 보여 주는 채널 수와 같아야 한다.
     * 정의가 갈라지면 사용자는 둘 중 무엇도 믿지 못한다.
     */
    @Test
    void matchesTheUsageScreen() {
        ProductUsageResponse usage = service.getUsage(twoMasters);
        long onScreen = usage.masterProducts().stream()
                .flatMap(master -> master.channels().stream())
                .map(ProductUsageResponse.ChannelRef::listingId)
                .distinct()
                .count();

        assertThat(service.countChannelsByProduct(List.of(twoMasters)))
                .containsEntry(twoMasters, (int) onScreen);
    }

    /** 🔴 쿼리 수는 물품 수와 무관하게 2 다 — 물품마다 세면 한 페이지가 수십 쿼리가 된다. */
    @Test
    void queryCountDoesNotGrowWithTheNumberOfProducts() {
        Statistics statistics = em.getEntityManager().getEntityManagerFactory()
                .unwrap(org.hibernate.SessionFactory.class).getStatistics();

        statistics.clear();
        service.countChannelsByProduct(List.of(twoMasters));
        long forOne = statistics.getPrepareStatementCount();

        em.clear();
        statistics.clear();
        service.countChannelsByProduct(List.of(twoMasters, noChannel, noMaster));
        long forThree = statistics.getPrepareStatementCount();

        assertThat(forOne).isEqualTo(2);
        assertThat(forThree).isEqualTo(forOne);
    }

    private Product product(String name) {
        Product saved = em.persist(Product.builder().productName(name).active(true).build());
        em.flush();
        return saved;
    }

    /** 마스터 + 구성상품 + 옵션 1개(그 물품 수량 벡터) — 실제 저장 모양과 같게 둘 다 만든다. */
    private MasterProduct master(String name, Product component) {
        MasterProduct master = em.persist(MasterProduct.builder().name(name).active(true).build());
        em.persist(MasterProductComponent.builder().masterProduct(master).product(component).build());
        MasterProductOption option =
                em.persist(MasterProductOption.builder().masterProduct(master).name("기본").build());
        em.persist(MasterProductOptionItem.builder().option(option).product(component).quantity(1).build());
        return master;
    }

    private void cell(MasterProduct master, Seller seller, String name) {
        em.persist(ProductListing.builder()
                .masterProduct(master).seller(seller).platform(Platform.COUPANG)
                .status(ListingStatus.SELLING).name(name).build());
    }
}
