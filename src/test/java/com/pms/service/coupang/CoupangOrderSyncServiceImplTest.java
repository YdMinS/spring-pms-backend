package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangOrderStatusSyncer.StatusSyncResult;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CoupangOrderSyncServiceImpl 오케스트레이션 테스트 — 상태별 실패 격리.
 *
 * 조회·upsert 는 CoupangOrderStatusSyncer 로 옮겨갔으므로(그쪽 테스트가 커버) 여기선 syncer 를 @Mock 으로 두고
 * "한 상태가 실패해도 나머지 상태를 계속 시도하고 결과를 유지하는가"만 검증한다(2026-09-02 사고 회귀).
 */
@ExtendWith(MockitoExtension.class)
class CoupangOrderSyncServiceImplTest {

    @Mock
    private CoupangOrderStatusSyncer statusSyncer;

    private CoupangOrderSyncServiceImpl service;
    private MarketplaceAccount account;

    private static final int STATUS_COUNT = CoupangOrderStatus.values().length;

    @BeforeEach
    void setUp() {
        service = new CoupangOrderSyncServiceImpl(statusSyncer, null);
        account = MarketplaceAccount.builder()
                .id(1L)
                .platform("COUPANG")
                .vendorId("V0001")
                .accessKey("ak")
                .secretKey("sk")
                .isActive(true)
                .build();
    }

    @Test
    void syncAccount_한상태실패시_나머지상태계속_그리고결과유지() {
        given(statusSyncer.syncStatus(any(), any())).willReturn(new StatusSyncResult(1, 1, 1));
        willThrow(new RestClientException("504 Gateway Timeout"))
                .given(statusSyncer).syncStatus(any(), eq(CoupangOrderStatus.FINAL_DELIVERY));

        SyncResult result = service.syncAccount(account);

        // 실패 상태 1개를 뺀 나머지가 그대로 집계된다 (예외 전파 없음).
        assertThat(result.newCount()).isEqualTo(STATUS_COUNT - 1);
        assertThat(result.updatedCount()).isEqualTo(STATUS_COUNT - 1);
        assertThat(result.failedStatuses()).containsExactly(CoupangOrderStatus.FINAL_DELIVERY);
        verify(statusSyncer, times(STATUS_COUNT)).syncStatus(any(), any());
    }

    @Test
    void syncAccount_전상태실패시_예외() {
        willThrow(new RestClientException("boom")).given(statusSyncer).syncStatus(any(), any());

        assertThatThrownBy(() -> service.syncAccount(account))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전 상태 실패");
    }

    @Test
    void syncAccount_전상태성공시_실패목록비어있음() {
        given(statusSyncer.syncStatus(any(), any())).willReturn(new StatusSyncResult(2, 0, 1));

        SyncResult result = service.syncAccount(account);

        assertThat(result.failedStatuses()).isEmpty();
        assertThat(result.newCount()).isEqualTo(2 * STATUS_COUNT);
        assertThat(result.pages()).isEqualTo(STATUS_COUNT);
    }

    @Test
    void syncResult_plus_실패목록이어붙임() {
        SyncResult a = new SyncResult(1, 2, 3, java.util.List.of(CoupangOrderStatus.ACCEPT));
        SyncResult b = new SyncResult(4, 5, 6, java.util.List.of(CoupangOrderStatus.ACCEPT,
                CoupangOrderStatus.INSTRUCT));

        SyncResult total = a.plus(b);

        assertThat(total.newCount()).isEqualTo(5);
        assertThat(total.updatedCount()).isEqualTo(7);
        assertThat(total.pages()).isEqualTo(9);
        // 계정 구분이 없으므로 distinct 하지 않는다(로그 용도).
        assertThat(total.failedStatuses()).containsExactly(
                CoupangOrderStatus.ACCEPT, CoupangOrderStatus.ACCEPT, CoupangOrderStatus.INSTRUCT);
        assertThatCode(() -> total.failedStatuses().add(CoupangOrderStatus.DEPARTURE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
