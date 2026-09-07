package com.pms.service.inquiry;

import com.pms.domain.Platform;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.MarketplaceAccount;

/**
 * 플랫폼별 고객문의 답변 전송 seam (FEATURE_2609_23 / PLAN D19).
 *
 * {@code InquirySyncAdapter}(01)·{@code ClaimActionAdapter}(2609_21)와 같은 관례다 — 서비스는
 * {@code List<InquiryReplyAdapter>} 를 주입받아 {@link #platform()} 으로 고른다. 어댑터가 없는
 * 플랫폼(네이버)은 {@code replyCapability.canReply = false} 로 내려가고(예외가 아니다 — 화면이 컴포저를
 * 그리지 않는다) 전송은 400 이다.
 *
 * <p>🔴 서비스·컨트롤러·응답 DTO 어디에도 {@code "COUPANG"} 문자열이 없어야 한다. 플랫폼을 아는 것은
 * {@code customer_inquiry.platform} 과 어댑터 구현뿐 — 그래야 네이버가 클래스 1개 추가로 붙는다.
 *
 * <p>어댑터는 <b>로컬 DB 를 건드리지 않는다.</b> 전송만 하고, 성공 시 로컬 반영은
 * {@code InquiryReplyRecorder} 가 한다.
 */
public interface InquiryReplyAdapter {

    /** {@code customer_inquiry.platform} 과 대조할 값. 예: {@link Platform#COUPANG}. */
    Platform platform();

    /**
     * 답변 1건 전송.
     *
     * @throws InquiryReplyFailedException 플랫폼이 거절함 (→400, 원문 메시지 포함)
     * @throws RuntimeException            타임아웃·네트워크 오류 = <b>성공 여부 불명</b> (→서비스가 502 로 바꾼다)
     */
    void reply(MarketplaceAccount account, CustomerInquiry inquiry, ReplyCommand command);

    /**
     * 전송 명령.
     *
     * @param content       strip 된 본문 — 검증·전송·저장이 <b>같은 값</b>을 쓴다
     * @param parentReplyId 고객센터 전용 상위 답변 식별자. <b>정책이 고른 값</b>이다
     */
    record ReplyCommand(String content, String parentReplyId) {
    }
}
