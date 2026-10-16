package com.pms.service.inquiry;

import com.pms.dto.request.InquiryReplyRequest;
import com.pms.dto.response.CustomerInquiryResponse;

/**
 * 고객문의 답변 전송의 진입점 (FEATURE_2609_23 / PLAN D5·D17·D19).
 *
 * <p>🔴 <b>호출자는 컨트롤러뿐이다</b> — 동기화 경로가 답변을 보내면 배치가 고객에게 글을 쓴다.
 */
public interface InquiryReplyService {

    /**
     * Sends one reply and returns the <b>updated inquiry</b>.
     *
     * The return is the <b>same type and assembly path</b> as {@code GET /api/inquiries/{id}} — the screen replaces
     * the whole inquiry object with the success response, so it must carry not only the thread but the related
     * order · product and the re-judged {@code replyCapability} (= {@code canReply false}).
     *
     * @throws com.pms.exception.ResourceNotFoundException no such inquiry (→404)
     * @throws IllegalArgumentException                    not repliable by policy · length out of range · already
     *                                                     sending · 11st non-delivery reason / carrier / invoice
     *                                                     missing or unknown (FEATURE_2610_17 / D68) (→400)
     * @throws InquiryReplyFailedException                 the platform rejected it (→400, its own message)
     * @throws com.pms.exception.BusinessException         send result unknown (timeout · network) (→502)
     */
    CustomerInquiryResponse reply(Long inquiryId, InquiryReplyRequest request);
}
