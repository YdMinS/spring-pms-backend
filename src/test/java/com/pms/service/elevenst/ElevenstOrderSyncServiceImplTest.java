package com.pms.service.elevenst;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.service.claim.ClaimSyncAdapter;
import com.pms.service.coupang.OrderSyncPreset;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.inquiry.InquirySyncAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D29 · D69: which lists a round reads and how failures are recorded (D10 · D11). */
@ExtendWith(MockitoExtension.class)
class ElevenstOrderSyncServiceImplTest {

    @Mock private ElevenstOrderApi orderApi;
    @Mock private ElevenstOrderUpserter orderUpserter;
    @Mock private ElevenstCancelRequestUpserter cancelRequestUpserter;
    @Mock private SyncStatusRecorder syncStatusRecorder;

    private final List<ClaimSyncAdapter> claimSyncAdapters = new ArrayList<>();
    private final List<InquirySyncAdapter> inquirySyncAdapters = new ArrayList<>();
    private ElevenstOrderSyncServiceImpl service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    @BeforeEach
    void setUp() {
        service = new ElevenstOrderSyncServiceImpl(orderApi, orderUpserter, cancelRequestUpserter,
                syncStatusRecorder, claimSyncAdapters, inquirySyncAdapters);
        lenient().when(orderApi.listByMinute(any(), anyString(), anyString(), any(), any())).thenReturn(List.of());
        lenient().when(orderUpserter.upsertRows(any(), any(), any())).thenReturn(UpsertCount.empty());
    }

    @Test
    void quick_readsReservedPaidAndPreparingTwiceEach_andThreeCancelLists() {
        service.sync(account, OrderSyncPreset.QUICK, false);

        verify(orderApi, times(2)).listByMinute(eq(account), eq(ElevenstPaths.ORDERS_RESERVED), eq("ordPrdSeq"), any(), any());
        verify(orderApi, times(2)).listByMinute(eq(account), eq(ElevenstPaths.ORDERS_PAID), eq("ordPrdSeq"), any(), any());
        verify(orderApi, times(2)).listByMinute(eq(account), eq(ElevenstPaths.ORDERS_PREPARING), eq("ordPrdSeq"), any(), any());
        verify(orderApi, never()).listByMinute(any(), eq(ElevenstPaths.ORDERS_SHIPPING), anyString(), any(), any());
        verify(orderApi).listByMinute(eq(account), eq(ElevenstPaths.CANCEL_REQUESTS), eq("ordPrdCnSeq"), any(), any());
        verify(orderApi).listByMinute(eq(account), eq(ElevenstPaths.CANCEL_COMPLETED), eq("ordPrdCnSeq"), any(), any());
        verify(orderApi).listByMinute(eq(account), eq(ElevenstPaths.CANCEL_WITHDRAWN), eq("ordPrdCnSeq"), any(), any());
        verify(syncStatusRecorder).recordSuccess(5L);
    }

    @Test
    void reconcile_addsShippingAndDelivered() {
        service.sync(account, OrderSyncPreset.RECONCILE, true);

        verify(orderApi, times(2)).listByMinute(eq(account), eq(ElevenstPaths.ORDERS_SHIPPING), anyString(), any(), any());
        verify(orderApi, times(2)).listByMinute(eq(account), eq(ElevenstPaths.ORDERS_DELIVERED), anyString(), any(), any());
    }

    @Test
    void orderFailure_manualRound_recordsFailureAndThrows() {
        BusinessException down = new BusinessException("개발 서버에서는 11번가를 부르지 않습니다", HttpStatus.SERVICE_UNAVAILABLE);
        given(orderApi.listByMinute(any(), eq(ElevenstPaths.ORDERS_PAID), anyString(), any(), any())).willThrow(down);

        assertThatThrownBy(() -> service.sync(account, OrderSyncPreset.QUICK, false)).isSameAs(down);

        verify(syncStatusRecorder).recordFailure(5L, down);
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    @Test
    void orderFailure_scheduledRound_recordsNothing() {
        given(orderApi.listByMinute(any(), eq(ElevenstPaths.ORDERS_PAID), anyString(), any(), any()))
                .willThrow(new IllegalStateException("x"));

        assertThatThrownBy(() -> service.sync(account, OrderSyncPreset.QUICK, true))
                .isInstanceOf(IllegalStateException.class);

        verify(syncStatusRecorder, never()).recordFailure(any(), any());
    }

    @Test
    void cancelFailure_recordsPartial_ordersDone() {
        given(orderApi.listByMinute(any(), eq(ElevenstPaths.CANCEL_REQUESTS), anyString(), any(), any()))
                .willThrow(new IllegalStateException("x"));

        assertThatThrownBy(() -> service.sync(account, OrderSyncPreset.QUICK, false))
                .isInstanceOf(IllegalStateException.class);

        verify(syncStatusRecorder).recordPartial(eq(5L), anyString(), eq(true), eq(false));
        verify(syncStatusRecorder, never()).recordPartial(any(), anyString(), anyBoolean(), eq(true));
    }
}
