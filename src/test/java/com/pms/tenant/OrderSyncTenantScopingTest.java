package com.pms.tenant;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.CoupangOrderSyncService;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import com.pms.service.coupang.CoupangReturnSyncService;
import com.pms.service.coupang.CoupangReturnSyncService.CancelSyncResult;
import com.pms.service.coupang.OrderSyncFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Proves the non-web tenant scoping of order sync (slice 03, Step 2).
 *
 * <p>Order sync can run without a SecurityContext (batch / future {@code @Scheduled}), so
 * {@code TenantContext} is empty. {@code OrderSyncFacadeImpl.syncOne} must drive the tenant from
 * the account being synced so saved {@code order_item}/{@code shopping_list_item} (@TenantId) land
 * in the account's tenant. Here we start with an empty context and assert the inner sync sees the
 * account's tenant.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisplayName("Order sync tenant scoping (non-web context)")
class OrderSyncTenantScopingTest {

    private static final Long TENANT_2 = 2L;

    @Autowired
    private OrderSyncFacade orderSyncFacade;

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private MarketplaceAccountRepository marketplaceAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private CoupangOrderSyncService coupangOrderSyncService;

    @MockBean
    private CoupangReturnSyncService coupangReturnSyncService;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbcTemplate.execute("delete from marketplace_account");   // FK child first
        jdbcTemplate.execute("delete from seller");
    }

    @Test
    @DisplayName("syncOne sets TenantContext from the account's tenant even with no web context")
    void syncScopesTenantFromAccount() {
        // Seed a seller + account under tenant 2.
        TenantContext.set(TENANT_2);
        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("t2-seller")
                .businessRegistration("222-22-22222")
                .build());
        Long accountId = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller)
                .platform("COUPANG")
                .accountAlias("t2-account")
                .vendorId("A00000002")
                .accessKey("access")
                .secretKey("secret")
                .isActive(true)
                .build()).getId();

        // Capture the tenant visible to the inner sync at invocation time.
        AtomicReference<Long> tenantSeenBySync = new AtomicReference<>();
        given(coupangOrderSyncService.syncAccount(any())).willAnswer(inv -> {
            tenantSeenBySync.set(TenantContext.get());
            return new SyncResult(0, 0, 0, java.util.List.of());
        });
        given(coupangReturnSyncService.syncCancels(any())).willReturn(new CancelSyncResult(0, 0));

        // Simulate a non-web trigger: no SecurityContext, empty TenantContext.
        TenantContext.clear();
        orderSyncFacade.sync(accountId);

        assertThat(tenantSeenBySync.get()).isEqualTo(TENANT_2);
    }
}
