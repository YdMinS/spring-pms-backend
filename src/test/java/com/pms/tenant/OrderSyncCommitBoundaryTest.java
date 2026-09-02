package com.pms.tenant;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangOrderStatus;
import com.pms.service.coupang.CoupangOrderSyncService;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;

/**
 * 동기화 커밋 경계 회귀 테스트 (PLAN D15, 2026-09-02 사고).
 *
 * <p>상태 루프가 한 트랜잭션에 묶여 있으면 뒤쪽 상태의 쿠팡 실패가 앞쪽 상태의 upsert 까지 롤백시켜
 * 그 계정 주문이 order_item 에 한 건도 남지 않는다 → 시트에는 나오는데 발송처리는 전량 미매칭.
 * 이 테스트가 없으면 누군가 {@code CoupangOrderSyncServiceImpl} 에 클래스 {@code @Transactional} 을
 * 되돌려도 아무도 모른다. 그래서 여기서는 {@code CoupangOrderSyncService} 를 mock 하지 않는다 —
 * 그게 검증 대상이다. mock 은 {@link CoupangApiClient} 뿐.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisplayName("Order sync commit boundary (per-status commit)")
class OrderSyncCommitBoundaryTest {

    private static final Long TENANT_3 = 3L;

    private static final String EMPTY_PAGE = "{\"data\":[],\"nextToken\":\"\"}";
    private static final String ONE_BOX = """
            {"data":[
              {"orderId":"O-CB1","shipmentBoxId":"B-CB1","status":"ACCEPT","paidAt":"2026-09-01T10:00:00+09:00",
               "orderItems":[{"vendorItemId":"I-CB1","vendorItemName":"양말","shippingCount":2}]}
            ],"nextToken":""}
            """;

    @Autowired
    private CoupangOrderSyncService coupangOrderSyncService;   // ★ 검증 대상 — mock 금지

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private MarketplaceAccountRepository marketplaceAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private CoupangApiClient coupangApiClient;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbcTemplate.execute("delete from order_item");            // FK child first
        jdbcTemplate.execute("delete from marketplace_account");
        jdbcTemplate.execute("delete from seller");
    }

    @Test
    @DisplayName("뒤쪽 상태가 실패해도 앞쪽 상태의 적재는 커밋되어 남는다")
    void earlierStatusesStayCommittedWhenLaterStatusFails() {
        TenantContext.set(TENANT_3);
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("cb-seller")
                .businessRegistration("333-33-33333")
                .build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller)
                .platform("COUPANG")
                .accountAlias("cb-account")
                .vendorId("A00000003")
                .accessKey("access")
                .secretKey("secret")
                .isActive(true)
                .build());

        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(EMPTY_PAGE);
        given(coupangApiClient.get(anyString(), argThat(statusIs(CoupangOrderStatus.ACCEPT)), any()))
                .willReturn(ONE_BOX);
        given(coupangApiClient.get(anyString(), argThat(statusIs(CoupangOrderStatus.FINAL_DELIVERY)), any()))
                .willThrow(new RestClientException("504 Gateway Timeout"));

        TenantContext.set(account.getTenantId());
        SyncResult result = coupangOrderSyncService.syncAccount(account);

        assertThat(result.failedStatuses()).containsExactly(CoupangOrderStatus.FINAL_DELIVERY);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_item WHERE marketplace_account_id = ?",
                Integer.class, account.getId());
        assertThat(rows).isGreaterThanOrEqualTo(1);   // ★ 앞 상태 커밋이 살아있다
    }

    private static org.mockito.ArgumentMatcher<String> statusIs(CoupangOrderStatus status) {
        return q -> q != null && q.contains("status=" + status.name());
    }
}
