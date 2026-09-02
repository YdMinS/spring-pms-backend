package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.domain.Seller;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 취소 재조정(recon) 대상 쿼리의 필터 회귀 테스트.
 *
 * 조회창 밖(paidAt 이 오래됐거나 null)의 발송전 주문이 대상에 섞이면 매칭 불가능한 주문에도
 * 매 동기화마다 쿠팡 1호출이 나가 동기화가 단조 증가로 느려진다(PLAN D9).
 *
 * AesAttributeConverter 는 @Component(생성자 주입) 라 @DataJpaTest 슬라이스에 명시 @Import 한다
 * (계정 secretKey 암복호화에 필요; master-key 는 application-test.yml).
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class OrderItemRepositoryTest {

    private static final List<String> PRE_SHIPMENT = List.of("ACCEPT", "INSTRUCT");

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private TestEntityManager em;

    private Long accountId;

    @BeforeEach
    void setUp() {
        // Do NOT set tenantId: @TenantId stamps it (NO_TENANT in this slice) and filters reads with the same value.
        Seller seller = Seller.builder()
                .sellerName("셀러A").businessRegistration("123-45-67890").build();
        em.persist(seller);
        MarketplaceAccount account = MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").vendorId("A00012345")
                .accessKey("ak").secretKey("sk").isActive(true).build();
        em.persist(account);
        accountId = account.getId();
    }

    @Test
    void findReconcilableExternalOrderIds_returnsOnlyPreShipmentInsideWindow() {
        LocalDateTime now = LocalDateTime.now();
        persistLine("A", "BOX-A", "ACCEPT", now.minusDays(1));       // 포함
        persistLine("B", "BOX-B", "ACCEPT", now.minusDays(60));      // 제외: 조회창 밖
        persistLine("C", "BOX-C", "DEPARTURE", now.minusDays(1));    // 제외: 발송 후
        persistLine("D", "BOX-D", "INSTRUCT", null);                 // 제외: paidAt 없음
        // 같은 주문번호의 다른 박스 라인 — DISTINCT 로 1건이어야 한다.
        persistLine("A", "BOX-A2", "ACCEPT", now.minusDays(1));
        em.flush();
        em.clear();

        List<String> orderIds = orderItemRepository.findReconcilableExternalOrderIds(
                accountId, PRE_SHIPMENT, now.minusDays(30));

        assertThat(orderIds).containsExactly("A");
    }

    private void persistLine(String orderId, String boxId, String status, LocalDateTime paidAt) {
        em.persist(OrderItem.builder()
                .marketplaceAccount(em.find(MarketplaceAccount.class, accountId))
                .platform("COUPANG")
                .externalOrderId(orderId)
                .externalBoxId(boxId)
                .externalItemId("ITEM-" + boxId)
                .orderCount(1).cancelCount(0).holdCount(0)
                .status(status)
                .paidAt(paidAt)
                .build());
    }
}
