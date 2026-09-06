package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.dto.response.ReplyCapability;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * InquiryReplyServiceImpl — 재판정 · 길이 · 전송 실패 격리 · 동시 전송 선점 (FEATURE_2609_23 / 04 Step 4).
 *
 * 로컬 반영은 {@link InquiryReplyRecorder}(REQUIRES_NEW) 담당이라 여기서는 호출 여부만 본다
 * (행 저장 자체는 InquiryReplyRecorderTest).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InquiryReplyServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 10, 0);

    @Mock private InquiryReplyPolicy inquiryReplyPolicy;
    @Mock private InquiryReplyRecorder inquiryReplyRecorder;
    @Mock private InquiryQueryService inquiryQueryService;
    @Mock private CustomerInquiryRepository customerInquiryRepository;
    @Mock private CustomerInquiryReplyRepository customerInquiryReplyRepository;
    @Mock private InquiryReplyAdapter adapter;

    private InquiryReplyServiceImpl service;

    private final MarketplaceAccount account = MarketplaceAccount.builder()
            .id(7L).platform("COUPANG").accountAlias("쿠팡-메인").vendorUserId("wing-user").build();

    @BeforeEach
    void setUp() {
        service = new InquiryReplyServiceImpl(inquiryReplyPolicy, inquiryReplyRecorder, inquiryQueryService,
                customerInquiryRepository, customerInquiryReplyRepository);

        given(customerInquiryRepository.findWithAccountById(3L)).willReturn(Optional.of(inquiry()));
        given(customerInquiryReplyRepository.findByInquiry_IdOrderByRepliedAtAsc(3L)).willReturn(List.of());
        given(adapter.platform()).willReturn("COUPANG");
        given(inquiryReplyPolicy.resolve("COUPANG")).willReturn(Optional.of(adapter));
    }

    @Test
    void reply_unknownInquiry_throwsNotFound() {
        given(customerInquiryRepository.findWithAccountById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reply(999L, "내일 출고 예정입니다."))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reply_policyBlocks_doesNotCallAdapterAndReportsReason() {
        given(inquiryReplyPolicy.evaluate(any(), any()))
                .willReturn(new ReplyCapability(false, "이미 답변한 문의입니다.", 1, 1000, true, null));

        assertThatThrownBy(() -> service.reply(3L, "내일 출고 예정입니다."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 답변한 문의입니다.");

        verify(adapter, never()).reply(any(), any(), any());
        verify(inquiryReplyRecorder, never()).record(anyLong(), anyString());
    }

    @Test
    void reply_tooShortAfterStrip_rejectsBeforeSending() {
        given(inquiryReplyPolicy.evaluate(any(), any()))
                .willReturn(new ReplyCapability(true, null, 2, 1000, true, "8123"));

        assertThatThrownBy(() -> service.reply(3L, "  네  "))     // strip 후 1자 < minLength 2
                .isInstanceOf(IllegalArgumentException.class);

        verify(adapter, never()).reply(any(), any(), any());
    }

    @Test
    void reply_success_sendsStrippedBodyAndRecordsLocally() {
        given(inquiryReplyPolicy.evaluate(any(), any()))
                .willReturn(new ReplyCapability(true, null, 1, 1000, true, "8123"));
        given(inquiryQueryService.getInquiry(3L)).willReturn(response(
                new ReplyCapability(false, "이미 답변한 문의입니다.", 1, 1000, true, null)));

        CustomerInquiryResponse result = service.reply(3L, "  내일 출고 예정입니다.  ");

        ArgumentCaptor<InquiryReplyAdapter.ReplyCommand> command =
                ArgumentCaptor.forClass(InquiryReplyAdapter.ReplyCommand.class);
        verify(adapter).reply(eq(account), any(), command.capture());
        // 검증·전송·저장이 같은 strip 된 값을 쓴다.
        assertThat(command.getValue().content()).isEqualTo("내일 출고 예정입니다.");
        // 클라이언트가 보낸 값이 아니라 정책이 고른 parentReplyId 를 쓴다.
        assertThat(command.getValue().parentReplyId()).isEqualTo("8123");
        verify(inquiryReplyRecorder).record(3L, "내일 출고 예정입니다.");

        // 성공 응답만으로 화면 컴포저가 잠긴다(D17) — 상세 조회와 같은 경로로 다시 조립한다.
        assertThat(result.replyCapability().canReply()).isFalse();
        assertThat(result.replyCapability().reason()).isEqualTo("이미 답변한 문의입니다.");
        assertThat(result.relatedOrder()).isNotNull();
    }

    @Test
    void reply_platformRejects_leavesLocalUntouched() {
        given(inquiryReplyPolicy.evaluate(any(), any()))
                .willReturn(new ReplyCapability(true, null, 1, 1000, true, null));
        willThrow(new InquiryReplyFailedException("이미 답변이 등록된 문의입니다."))
                .given(adapter).reply(any(), any(), any());

        assertThatThrownBy(() -> service.reply(3L, "내일 출고 예정입니다."))
                .isInstanceOf(InquiryReplyFailedException.class)
                .hasMessage("이미 답변이 등록된 문의입니다.");

        verify(inquiryReplyRecorder, never()).record(anyLong(), anyString());
        verify(customerInquiryReplyRepository, never()).save(any());
        verify(customerInquiryRepository, never()).save(any());
    }

    @Test
    void reply_timeout_mapsToBadGatewayAndLeavesLocalUntouched() {
        given(inquiryReplyPolicy.evaluate(any(), any()))
                .willReturn(new ReplyCapability(true, null, 1, 1000, true, null));
        willThrow(new RuntimeException("I/O error: read timed out"))
                .given(adapter).reply(any(), any(), any());

        assertThatThrownBy(() -> service.reply(3L, "내일 출고 예정입니다."))
                .isInstanceOf(BusinessException.class)
                .hasMessage("전송 결과를 확인하지 못했습니다. 다음 동기화 후 답변 여부를 확인해 주세요.")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);

        verify(inquiryReplyRecorder, never()).record(anyLong(), anyString());
        verify(customerInquiryRepository, never()).save(any());
    }

    @Test
    void reply_secondRequestWhileInFlight_isRejectedWithoutSending() {
        given(inquiryReplyPolicy.evaluate(any(), any()))
                .willReturn(new ReplyCapability(true, null, 1, 1000, true, null));
        given(inquiryQueryService.getInquiry(3L)).willReturn(response(
                new ReplyCapability(false, "이미 답변한 문의입니다.", 1, 1000, true, null)));

        // 전송이 진행되는 동안 같은 문의로 두 번째 요청이 들어온 상황을 재현한다.
        willAnswer(invocation -> {
            assertThatThrownBy(() -> service.reply(3L, "두 번째 전송"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("이미 전송 중인 문의입니다.");
            return null;
        }).given(adapter).reply(any(), any(), any());

        service.reply(3L, "내일 출고 예정입니다.");

        verify(adapter, times(1)).reply(any(), any(), any());
        verify(inquiryReplyRecorder, times(1)).record(anyLong(), anyString());
    }

    @Test
    void reply_afterFailure_releasesInFlightSlot() {
        given(inquiryReplyPolicy.evaluate(any(), any()))
                .willReturn(new ReplyCapability(true, null, 1, 1000, true, null));
        willThrow(new InquiryReplyFailedException("빈 본문입니다."))
                .given(adapter).reply(any(), any(), any());

        assertThatThrownBy(() -> service.reply(3L, "내일 출고 예정입니다."))
                .isInstanceOf(InquiryReplyFailedException.class);

        // 선점 해제가 finally 에 있어야 사용자가 본문을 고쳐 재시도할 수 있다.
        assertThatThrownBy(() -> service.reply(3L, "내일 출고 예정입니다."))
                .isInstanceOf(InquiryReplyFailedException.class);
        verify(adapter, times(2)).reply(any(), any(), any());
    }

    private CustomerInquiry inquiry() {
        return CustomerInquiry.builder()
                .id(3L)
                .marketplaceAccount(account)
                .platform("COUPANG")
                .inquiryType(InquiryType.CALL_CENTER)
                .externalInquiryId("7777")
                .status(InquiryStatus.UNANSWERED)
                .platformStatus("progress/requestAnswer")
                .inquiredAt(NOW.minusDays(1))
                .lastSyncedAt(NOW)
                .build();
    }

    private CustomerInquiryResponse response(ReplyCapability capability) {
        return new CustomerInquiryResponse(3L, "COUPANG", 7L, "쿠팡-메인", 5L, "테스트셀러",
                InquiryType.CALL_CENTER, InquiryStatus.ANSWERED, "progress/answered",
                "7777", "O-1", "V-1", null, null, 11L, "양말", "재입고 문의", "배송",
                NOW.minusDays(1), NOW, true, List.of(replyResponse()),
                new com.pms.dto.response.InquiryRelatedOrderResponse("O-1", NOW.minusDays(2),
                        "홍길동", "홍길동", List.of()),
                null, capability);
    }

    private com.pms.dto.response.CustomerInquiryReplyResponse replyResponse() {
        return new com.pms.dto.response.CustomerInquiryReplyResponse(101L, "local-abc", null,
                InquiryAuthorRole.SELLER, null, "내일 출고 예정입니다.", null, NOW);
    }
}
