package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangOrderStatusSyncer.StatusSyncResult;
import com.pms.service.coupang.CoupangOrderSyncService.SyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private CoupangProperties coupangProperties;

    private CoupangOrderSyncServiceImpl service;
    private MarketplaceAccount account;

    private static final int STATUS_COUNT = CoupangOrderStatus.values().length;

    @BeforeEach
    void setUp() {
        service = new CoupangOrderSyncServiceImpl(statusSyncer, null, coupangProperties);
        // 스텁이 없으면 int 기본값 0 → from == to 인 창이 만들어져 창 단언이 조용히 어긋난다.
        lenient().when(coupangProperties.getSyncDays()).thenReturn(14);
        lenient().when(coupangProperties.getTerminalSyncMinDays()).thenReturn(3);
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
        given(statusSyncer.syncStatus(any(), any(), any())).willReturn(new StatusSyncResult(1, 1, 1));
        willThrow(new RestClientException("504 Gateway Timeout"))
                .given(statusSyncer).syncStatus(any(), eq(CoupangOrderStatus.FINAL_DELIVERY), any());

        SyncResult result = service.syncAccount(account);

        // 실패 상태 1개를 뺀 나머지가 그대로 집계된다 (예외 전파 없음).
        assertThat(result.newCount()).isEqualTo(STATUS_COUNT - 1);
        assertThat(result.updatedCount()).isEqualTo(STATUS_COUNT - 1);
        assertThat(result.failedStatuses()).containsExactly(CoupangOrderStatus.FINAL_DELIVERY);
        verify(statusSyncer, times(STATUS_COUNT)).syncStatus(any(), any(), any());
    }

    @Test
    void syncAccount_전상태실패시_예외() {
        willThrow(new RestClientException("boom")).given(statusSyncer).syncStatus(any(), any(), any());

        assertThatThrownBy(() -> service.syncAccount(account))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전 상태 실패");
    }

    @Test
    void syncAccount_전상태성공시_실패목록비어있음() {
        given(statusSyncer.syncStatus(any(), any(), any())).willReturn(new StatusSyncResult(2, 0, 1));

        SyncResult result = service.syncAccount(account);

        assertThat(result.failedStatuses()).isEmpty();
        assertThat(result.newCount()).isEqualTo(2 * STATUS_COUNT);
        assertThat(result.pages()).isEqualTo(STATUS_COUNT);
    }

    @Test
    void syncAccount_무인자버전은_기본창을_넘긴다() {
        given(statusSyncer.syncStatus(any(), any(), any())).willReturn(new StatusSyncResult(0, 0, 1));

        service.syncAccount(account);

        // 기본 창은 호출자(이 클래스)가 만든다(D6): 오늘(KST) − sync-days ~ 오늘(KST).
        ArgumentCaptor<SyncWindow> window = ArgumentCaptor.forClass(SyncWindow.class);
        verify(statusSyncer, times(STATUS_COUNT)).syncStatus(any(), any(), window.capture());
        LocalDate today = LocalDate.now(SyncWindow.KST);
        assertThat(window.getValue().to()).isEqualTo(today);
        assertThat(window.getValue().from()).isEqualTo(today.minusDays(14));
    }

    @Test
    void syncAccount_지정창은_그대로_syncer에_전달된다() {
        given(statusSyncer.syncStatus(any(), any(), any())).willReturn(new StatusSyncResult(0, 0, 1));
        SyncWindow requested = new SyncWindow(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        service.syncAccount(account, requested);

        ArgumentCaptor<SyncWindow> window = ArgumentCaptor.forClass(SyncWindow.class);
        verify(statusSyncer, times(STATUS_COUNT)).syncStatus(any(), any(), window.capture());
        assertThat(window.getValue()).isEqualTo(requested);
    }

    /** 캡처한 상태·창 두 리스트를 상태 → 창 길이(일) 맵으로 zip 한다. 캡처 순서에 기대지 않기 위한 헬퍼. */
    private static Map<CoupangOrderStatus, Long> zipToDays(List<CoupangOrderStatus> statuses, List<SyncWindow> windows) {
        Map<CoupangOrderStatus, Long> days = new EnumMap<>(CoupangOrderStatus.class);
        for (int i = 0; i < statuses.size(); i++) {
            days.put(statuses.get(i), ChronoUnit.DAYS.between(windows.get(i).from(), windows.get(i).to()));
        }
        return days;
    }

    private Map<CoupangOrderStatus, Long> captureWindowLengths() {
        ArgumentCaptor<CoupangOrderStatus> statuses = ArgumentCaptor.forClass(CoupangOrderStatus.class);
        ArgumentCaptor<SyncWindow> windows = ArgumentCaptor.forClass(SyncWindow.class);
        verify(statusSyncer, times(STATUS_COUNT)).syncStatus(eq(account), statuses.capture(), windows.capture());
        return zipToDays(statuses.getAllValues(), windows.getAllValues());
    }

    /** lastOrderSyncAt 은 서버 시각(UTC) naive 다 — 테스트 데이터도 UTC 로 고정한다(오후에만 깨지는 것 방지). */
    private static LocalDateTime utcDaysAgo(long days) {
        return LocalDate.now(SyncWindow.KST).minusDays(days).atTime(3, 0);
    }

    @Test
    void syncAccount_종결상태는좁은창_활성상태는기본창() {
        given(statusSyncer.syncStatus(any(), any(), any())).willReturn(new StatusSyncResult(0, 0, 1));
        account = account.toBuilder().lastOrderSyncAt(utcDaysAgo(1)).build();

        service.syncAccount(account);

        Map<CoupangOrderStatus, Long> lengths = captureWindowLengths();
        assertThat(lengths.get(CoupangOrderStatus.ACCEPT)).isEqualTo(14);
        assertThat(lengths.get(CoupangOrderStatus.INSTRUCT)).isEqualTo(14);
        assertThat(lengths.get(CoupangOrderStatus.DEPARTURE)).isEqualTo(3);
        assertThat(lengths.get(CoupangOrderStatus.DELIVERING)).isEqualTo(3);
        assertThat(lengths.get(CoupangOrderStatus.FINAL_DELIVERY)).isEqualTo(3);
        assertThat(lengths.get(CoupangOrderStatus.NONE_TRACKING)).isEqualTo(3);
    }

    @Test
    void syncAccount_명시창은_전상태동일() {
        // 기간 백필(D7): 지정 창에는 축소를 끼얹지 않는다.
        given(statusSyncer.syncStatus(any(), any(), any())).willReturn(new StatusSyncResult(0, 0, 1));
        SyncWindow requested = new SyncWindow(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 21));

        service.syncAccount(account, requested);

        ArgumentCaptor<SyncWindow> windows = ArgumentCaptor.forClass(SyncWindow.class);
        verify(statusSyncer, times(STATUS_COUNT)).syncStatus(any(), any(), windows.capture());
        assertThat(windows.getAllValues()).containsOnly(requested);
    }

    @Test
    void syncAccount_마지막성공없으면_전상태기본창() {
        // 한 번도 성공 못 한 계정 = 현행 동작 그대로(D4).
        given(statusSyncer.syncStatus(any(), any(), any())).willReturn(new StatusSyncResult(0, 0, 1));

        service.syncAccount(account);

        assertThat(captureWindowLengths().values()).containsOnly(14L);
    }

    @Test
    void syncAccount_상태별창이달라도_실패격리는그대로() {
        given(statusSyncer.syncStatus(any(), any(), any())).willReturn(new StatusSyncResult(1, 1, 1));
        willThrow(new RestClientException("504 Gateway Timeout"))
                .given(statusSyncer).syncStatus(any(), eq(CoupangOrderStatus.DELIVERING), any());
        account = account.toBuilder().lastOrderSyncAt(utcDaysAgo(1)).build();

        SyncResult result = service.syncAccount(account);

        assertThat(result.newCount()).isEqualTo(STATUS_COUNT - 1);
        assertThat(result.failedStatuses()).containsExactly(CoupangOrderStatus.DELIVERING);
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
