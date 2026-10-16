package com.pms.service.inquiry;

import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Platform-neutral transfer object between a parser and {@code InquiryUpserter} (FEATURE_2609_23 / D3).
 *
 * Parsers are <b>fully separate</b> per type (the response schemas share not one field), but they all return this
 * one type — keeping only the stored shape the same is the core of D1·D3. 11st fills it from Q&A 1626 and urgent
 * notices 1796 (FEATURE_2610_17 / D16 · D26).
 *
 * <p>🔴 Do not add buyer phone / email fields (D13). Call-center (and 11st) responses carry them, but they are not kept.
 *
 * @param externalOrderId   nullable — a product Q&A without an order is normal (D14)
 * @param externalProductId nullable — product Q&A only: Coupang sellerProductId · 11st prdNo ({@code brdInfoClfNo})
 * @param itemName          nullable — Coupang product Q&A has none and is filled by the cell link (D15); 11st sends prdNm
 * @param category          nullable — Coupang call-center receiptCategory raw value; 11st leaves it null
 * @param platformStatus    raw status (kept without normalization loss, D7)
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
