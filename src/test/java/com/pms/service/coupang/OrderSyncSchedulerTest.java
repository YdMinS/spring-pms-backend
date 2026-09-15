package com.pms.service.coupang;

import com.pms.repository.MarketplaceAccountRepository;
import com.pms.security.TenantContext;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;

/**
 * OrderSyncScheduler — 테넌트 명시 순회, 테넌트 단위 격리, 프리셋 전달(FEATURE_2609_49 / D1·D2).
 *
 * <p>cron 발화 자체는 검증하지 않는다(Spring 이 하는 일) — 여기서는 "발화하면 무엇을 하는가"만 본다.
 */
@ExtendWith(MockitoExtension.class)
class OrderSyncSchedulerTest {

    @Mock private OrderSyncFacade orderSyncFacade;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;

    @InjectMocks private OrderSyncScheduler scheduler;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void syncQuick_runsOncePerTenant_withThatTenantInContext() {
        given(marketplaceAccountRepository.findDistinctTenantIds()).willReturn(List.of(1L, 2L));
        // 🔴 파사드 호출 시점에 캡처한다 — 메서드가 반환된 뒤에는 이미 clear 다.
        List<Long> seen = new ArrayList<>();
        willAnswer(inv -> {
            seen.add(TenantContext.get());
            return OrderSyncResult.empty();
        }).given(orderSyncFacade).syncAll(OrderSyncPreset.QUICK);

        scheduler.syncQuick();

        verify(orderSyncFacade, org.mockito.Mockito.times(2)).syncAll(OrderSyncPreset.QUICK);
        assertThat(seen).containsExactly(1L, 2L);
    }

    @Test
    void syncQuick_oneTenantThrows_othersStillRun() {
        given(marketplaceAccountRepository.findDistinctTenantIds()).willReturn(List.of(1L, 2L));
        List<Long> seen = new ArrayList<>();
        willAnswer(inv -> {
            Long tenant = TenantContext.get();
            seen.add(tenant);
            if (tenant == 1L) {
                throw new RuntimeException("tenant 1 down");
            }
            return OrderSyncResult.empty();
        }).given(orderSyncFacade).syncAll(any(OrderSyncPreset.class));

        scheduler.syncQuick();

        assertThat(seen).containsExactly(1L, 2L);
    }

    @Test
    void syncQuick_clearsTenantAfterRun() {
        given(marketplaceAccountRepository.findDistinctTenantIds()).willReturn(List.of(1L));
        given(orderSyncFacade.syncAll(OrderSyncPreset.QUICK)).willReturn(OrderSyncResult.empty());

        scheduler.syncQuick();

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void syncReconcile_usesReconcilePreset() {
        given(marketplaceAccountRepository.findDistinctTenantIds()).willReturn(List.of(1L));
        given(orderSyncFacade.syncAll(OrderSyncPreset.RECONCILE)).willReturn(OrderSyncResult.empty());

        scheduler.syncReconcile();

        verify(orderSyncFacade).syncAll(OrderSyncPreset.RECONCILE);
    }
}
