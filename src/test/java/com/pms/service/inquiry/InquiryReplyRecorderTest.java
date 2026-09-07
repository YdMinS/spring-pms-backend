package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * InquiryReplyRecorder — 전송 성공 후 로컬 반영 (FEATURE_2609_23 / 04 Step 4).
 *
 * 쿠팡이 답변 ID 를 돌려주지 않아 로컬 임시 행을 {@code local-} 접두로 넣는다. 다음 동기화가 같은 답변을
 * 진짜 ID 로 실어오면 {@code InquiryUpserter} 가 내용이 같은 이 행만 지운다(01 Step 6-5 개정).
 */
@ExtendWith(MockitoExtension.class)
class InquiryReplyRecorderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 10, 0);

    @Mock private CustomerInquiryRepository customerInquiryRepository;
    @Mock private CustomerInquiryReplyRepository customerInquiryReplyRepository;
    @InjectMocks private InquiryReplyRecorder recorder;

    @Test
    void record_appendsLocalSellerReplyAndMarksAnswered() {
        given(customerInquiryRepository.findById(3L)).willReturn(Optional.of(inquiry()));

        recorder.record(3L, "내일 출고 예정입니다.");

        ArgumentCaptor<CustomerInquiryReply> reply = ArgumentCaptor.forClass(CustomerInquiryReply.class);
        verify(customerInquiryReplyRepository).save(reply.capture());
        assertThat(reply.getValue().getAuthorRole()).isEqualTo(InquiryAuthorRole.SELLER);
        assertThat(reply.getValue().getAuthorName()).isNull();
        assertThat(reply.getValue().getContent()).isEqualTo("내일 출고 예정입니다.");
        assertThat(reply.getValue().getExternalReplyId()).startsWith("local-");
        assertThat(reply.getValue().getRepliedAt()).isNotNull();

        ArgumentCaptor<CustomerInquiry> saved = ArgumentCaptor.forClass(CustomerInquiry.class);
        verify(customerInquiryRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(saved.getValue().getAnsweredAt()).isNotNull();
        assertThat(saved.getValue().getId()).isEqualTo(3L);      // 새 행이 아니라 같은 문의
    }

    @Test
    void record_unknownInquiry_throwsNotFound() {
        given(customerInquiryRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.record(999L, "내일 출고 예정입니다."))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private CustomerInquiry inquiry() {
        return CustomerInquiry.builder()
                .id(3L)
                .marketplaceAccount(MarketplaceAccount.builder().id(7L).platform(Platform.COUPANG).build())
                .platform(Platform.COUPANG)
                .inquiryType(InquiryType.PRODUCT_QNA)
                .externalInquiryId("5001")
                .status(InquiryStatus.UNANSWERED)
                .platformStatus("NOANSWER")
                .inquiredAt(NOW.minusDays(1))
                .lastSyncedAt(NOW)
                .build();
    }
}
