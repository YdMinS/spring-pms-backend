package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SyncStatus;
import com.pms.repository.MarketplaceAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * 기록기/기록 쓰기 — 부분 성공 시 미완료 단계 시각 보존, 응답 바디 없는 실패 요약,
 * 기록 실패가 동기화를 깨지 않는 계약.
 *
 * REQUIRES_NEW 가 "롤백돼도 기록이 남는다"는 성질 자체는 단위 테스트로 못 잡는다 —
 * 근거는 SyncStatusWriter 클래스 주석이고, 여기서는 위 상호작용까지만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SyncStatusRecorderTest {

    private static final LocalDateTime PREVIOUS_CANCEL_SYNC = LocalDateTime.of(2026, 9, 1, 10, 0);

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private SyncStatusWriter writer;

    private MarketplaceAccount account() {
        return MarketplaceAccount.builder()
                .id(1L).platform("COUPANG").vendorId("V1")
                .accessKey("ak").secretKey("sk").isActive(true)
                .lastCancelSyncAt(PREVIOUS_CANCEL_SYNC)
                .build();
    }

    @Test
    void recordPartial_keepsLastCancelSyncAt() {
        SyncStatusWriter realWriter = new SyncStatusWriter(marketplaceAccountRepository);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(account()));

        realWriter.writePartial(1L, "취소 보정 실패 — HTTP 504 from Coupang", true, false);

        ArgumentCaptor<MarketplaceAccount> captor = ArgumentCaptor.forClass(MarketplaceAccount.class);
        verify(marketplaceAccountRepository).save(captor.capture());
        MarketplaceAccount saved = captor.getValue();
        assertThat(saved.getLastSyncStatus()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(saved.getLastOrderSyncAt()).isNotNull();                       // 완료된 단계만 갱신
        assertThat(saved.getLastCancelSyncAt()).isEqualTo(PREVIOUS_CANCEL_SYNC);  // 미완료 단계는 미변경
        assertThat(saved.getLastSyncError()).isEqualTo("취소 보정 실패 — HTTP 504 from Coupang");
    }

    @Test
    void recordFailure_summarizesWithoutResponseBody() {
        SyncStatusWriter realWriter = new SyncStatusWriter(marketplaceAccountRepository);
        SyncStatusRecorder recorder = new SyncStatusRecorder(realWriter);
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(account()));

        recorder.recordFailure(1L, new RestClientResponseException(
                "500 Internal Server Error", 500, "Internal Server Error", null,
                "{\"secret\":\"should-not-leak\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        ArgumentCaptor<MarketplaceAccount> captor = ArgumentCaptor.forClass(MarketplaceAccount.class);
        verify(marketplaceAccountRepository).save(captor.capture());
        String error = captor.getValue().getLastSyncError();
        assertThat(error).isEqualTo("HTTP 500 from Coupang");
        assertThat(error).doesNotContain("should-not-leak");
        assertThat(error.length()).isLessThanOrEqualTo(500);
        assertThat(captor.getValue().getLastSyncStatus()).isEqualTo(SyncStatus.FAILED);
    }

    @Test
    void recordSuccess_doesNotThrow_whenWriterFails() {
        SyncStatusRecorder recorder = new SyncStatusRecorder(writer);
        willThrow(new RuntimeException("commit failed")).given(writer).writeSuccess(1L);

        // 기록 실패가 동기화를 깨지 않는다는 계약
        assertThatCode(() -> recorder.recordSuccess(1L)).doesNotThrowAnyException();
    }
}
