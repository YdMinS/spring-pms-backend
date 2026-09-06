package com.pms.service.inquiry;

import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 파서 → {@code InquiryUpserter} 사이의 플랫폼 중립 전달 객체 (FEATURE_2609_23 / D3).
 *
 * 파서는 유형별로 <b>완전히 분리</b>돼 있지만(응답 스키마가 한 필드도 겹치지 않는다) 반환 타입은
 * 이것 하나다 — 저장 형태만 같게 맞추는 것이 D1·D3 의 핵심이다.
 *
 * <p>🔴 구매자 연락처·이메일 필드를 추가하지 말 것(D13). 고객센터 응답에는 실려 오지만 담지 않는다.
 *
 * @param externalOrderId  nullable — 주문 없는 상품문의가 정상이다 (D14)
 * @param externalProductId nullable — sellerProductId, 상품문의 전용
 * @param itemName         nullable — 상품문의에는 없어서 셀 연결(D15) 때 채운다
 * @param category         nullable — receiptCategory 원문, 고객센터 전용
 * @param platformStatus   원문 상태 (정규화 손실 없이 보존, D7)
 */
public record InquiryRecord(
        InquiryType type,
        String externalInquiryId,
        String externalItemId,
        String externalOrderId,
        String externalProductId,
        String itemName,
        String content,
        String category,
        InquiryStatus status,
        String platformStatus,
        LocalDateTime inquiredAt,
        LocalDateTime answeredAt,
        List<ReplyRecord> replies) {

    /**
     * 답변 1건.
     *
     * @param parentExternalReplyId nullable — 고객센터 {@code parentAnswerId}. 답변 전송(04)의 필수 값이라
     *                              조회 단계에서 반드시 보존한다
     * @param authorName            nullable — 쿠팡 상담사 이름(고객 PII 아님, D13)
     * @param transferStatus        nullable — {@code partnerTransferStatus} 원문, 고객센터 전용
     */
    public record ReplyRecord(
            String externalReplyId,
            String parentExternalReplyId,
            InquiryAuthorRole authorRole,
            String authorName,
            String content,
            String transferStatus,
            LocalDateTime repliedAt) {
    }
}
