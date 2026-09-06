package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CustomerInquiryReplyRepository;
import com.pms.repository.CustomerInquiryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 답변 전송 성공 후 로컬 반영 (FEATURE_2609_23 / PLAN D17 · 04 Step 4).
 *
 * ⚠️ {@code REQUIRES_NEW} — 호출자({@link InquiryReplyServiceImpl})는 외부 HTTP 를 도는 경로라
 * 트랜잭션이 없다. 쓰기만 짧게 자체 트랜잭션으로 커밋한다({@code ClaimUpserter}·{@code InquiryUpserter}
 * 와 같은 관례).
 *
 * <p>🔴 <b>전송이 성공했을 때만</b> 부른다. 전송 실패에 로컬을 건드리면 보내지지 않은 답변이 화면에 남는다.
 * <p>⚠️ 반대로 여기가 실패해도 보정 배치를 만들지 않는다 — 다음 동기화가 진짜 답변을 실어오며
 * {@code ANSWERED} 로 정정한다(01 Step 6).
 */
@Component
@RequiredArgsConstructor
public class InquiryReplyRecorder {

    /**
     * 로컬 임시 답변 식별자 접두.
     *
     * 쿠팡이 답변 ID 를 돌려주지 않아서(응답은 {@code code}/{@code message} 뿐) 진짜 식별자를 알 수 없다.
     * 다음 동기화가 같은 답변을 진짜 ID 로 실어오면 {@code InquiryUpserter} 가 <b>내용이 같은</b>
     * 이 접두의 행만 지운다 — 접두를 바꾸려면 그쪽도 함께 바꿔야 한다.
     */
    static final String LOCAL_REPLY_PREFIX = "local-";

    private final CustomerInquiryRepository customerInquiryRepository;
    private final CustomerInquiryReplyRepository customerInquiryReplyRepository;

    /**
     * 답변 1행 추가 + 문의를 {@code ANSWERED} 로 전이.
     *
     * <p>문의를 <b>id 로 다시 읽는다</b> — 호출자가 트랜잭션 밖에서 들고 있던 엔티티는 detached 라
     * 그대로 merge 하면 lazy 컬렉션(replies)까지 얽힌다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long inquiryId, String content) {
        CustomerInquiry inquiry = customerInquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ResourceNotFoundException("Inquiry", inquiryId));
        LocalDateTime now = LocalDateTime.now();

        customerInquiryReplyRepository.save(CustomerInquiryReply.builder()
                .inquiry(inquiry)
                .externalReplyId(LOCAL_REPLY_PREFIX + UUID.randomUUID())
                .authorRole(InquiryAuthorRole.SELLER)
                .authorName(null)                   // 판매자 답변에는 작성자명을 싣지 않는다(상담사 전용 필드)
                .content(content)
                .repliedAt(now)
                .build());

        customerInquiryRepository.save(inquiry.toBuilder()
                .status(InquiryStatus.ANSWERED)
                .answeredAt(now)
                .build());
    }
}
