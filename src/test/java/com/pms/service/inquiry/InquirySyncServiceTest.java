package com.pms.service.inquiry;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.InquirySyncResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.inquiry.InquirySyncAdapter.InquirySyncResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * InquirySyncService — 문의만 다시 가져오기.
 *
 * 관심사는 셋이다: 적재를 어댑터에 맡기는가(재구현 금지) · 성공한 회차에만 마지막 조회 시각을
 * 기록하는가 · 부를 어댑터가 없을 때 400 으로 떨어지는가.
 */
@ExtendWith(MockitoExtension.class)
class InquirySyncServiceTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private InquirySyncAdapter coupangAdapter;
    @Mock private SyncStatusRecorder syncStatusRecorder;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A0001", null)
            .id(7L).platform(Platform.COUPANG).build();

    private InquirySyncServiceImpl service() {
        return new InquirySyncServiceImpl(marketplaceAccountRepository, List.of(coupangAdapter),
                syncStatusRecorder);
    }

    @Test
    void sync_delegatesToAdapterAndRecordsCompletion() {
        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(account));
        given(coupangAdapter.platform()).willReturn(Platform.COUPANG);
        given(coupangAdapter.syncInquiries(account)).willReturn(new InquirySyncResult(2, 3, 5, 1));

        InquirySyncResponse response = service().sync(7L);

        assertThat(response.getAccountId()).isEqualTo(7L);
        assertThat(response.getFetched()).isEqualTo(5);
        assertThat(response.getStaleClosed()).isEqualTo(1);
        verify(coupangAdapter).syncInquiries(account);
        verify(syncStatusRecorder).recordInquirySyncCompleted(7L);
    }

    @Test
    void sync_adapterFails_propagatesAndDoesNotRecord() {
        // 실패한 회차에 시각을 갱신하면 다음 회차가 놓친 구간을 다시 읽지 않는다.
        given(marketplaceAccountRepository.findById(7L)).willReturn(Optional.of(account));
        given(coupangAdapter.platform()).willReturn(Platform.COUPANG);
        given(coupangAdapter.syncInquiries(account)).willThrow(new IllegalStateException("쿠팡 응답 실패"));

        assertThatThrownBy(() -> service().sync(7L)).isInstanceOf(IllegalStateException.class);

        verify(syncStatusRecorder, never()).recordInquirySyncCompleted(anyLong());
    }

    @Test
    void sync_unknownAccount_throwsNotFound() {
        given(marketplaceAccountRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().sync(999L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sync_platformWithoutAdapter_throwsBadRequest() {
        MarketplaceAccount naver = MarketplaceAccountFixture.coupangStubBuilder("A0002", null)
                .id(8L).platform(Platform.NAVER).build();
        given(marketplaceAccountRepository.findById(8L)).willReturn(Optional.of(naver));
        given(coupangAdapter.platform()).willReturn(Platform.COUPANG);

        assertThatThrownBy(() -> service().sync(8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는");

        verify(coupangAdapter, never()).syncInquiries(naver);
    }
}
