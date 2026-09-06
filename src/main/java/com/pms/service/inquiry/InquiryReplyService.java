package com.pms.service.inquiry;

import com.pms.dto.response.CustomerInquiryResponse;

/**
 * 고객문의 답변 전송의 진입점 (FEATURE_2609_23 / PLAN D5·D17·D19).
 *
 * <p>🔴 <b>호출자는 컨트롤러뿐이다</b> — 동기화 경로가 답변을 보내면 배치가 고객에게 글을 쓴다.
 */
public interface InquiryReplyService {

    /**
     * 답변 1건 전송 후 <b>갱신된 문의</b>를 돌려준다.
     *
     * 반환은 {@code GET /api/inquiries/{id}} 와 <b>같은 타입·같은 조립 경로</b>다 — 화면이 성공 응답으로
     * 문의 객체를 통째로 교체하므로 스레드뿐 아니라 관련 주문·상품과 재판정된 {@code replyCapability}
     * (= {@code canReply false})까지 실려야 한다.
     *
     * @throws com.pms.exception.ResourceNotFoundException 없는 문의 (→404)
     * @throws IllegalArgumentException                    정책상 불가 · 길이 위반 · 동시 전송 (→400)
     * @throws InquiryReplyFailedException                 플랫폼이 거절 (→400, 원문 메시지)
     * @throws com.pms.exception.BusinessException         전송 결과 불명(타임아웃·네트워크) (→502)
     */
    CustomerInquiryResponse reply(Long inquiryId, String content);
}
