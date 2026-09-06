package com.pms.dto.response;

import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 고객문의 응답 DTO — GET /api/inquiries, GET /api/inquiries/{id} (FEATURE_2609_23).
 *
 * {@code platform} 은 클라이언트가 채널을 구분하는 근거라 목록부터 내려보낸다({@code OrderClaimResponse}
 * 와 같은 판단). {@code orderItemId} 가 null 이면 주문 라인 미연결(D15)이며, 화면은 {@code linked} 로
 * 배지를 띄우고 {@code itemName} 으로 최소 정보를 보여준다.
 *
 * <p>{@code replies}·{@code relatedOrder}·{@code relatedListing} 은 <b>단건 조회에서만</b> 채워진다 —
 * 목록에서 스레드를 fetch 하면 화면 하나가 답변 수만큼 쿼리를 끈다(PLAN §3).
 *
 * <p>{@code replyCapability}(PLAN §5·D5)도 <b>단건 조회와 답변 전송 성공 응답에서만</b> 채워진다 —
 * 목록에서 채우려면 행마다 계정을 들여다봐야 하고 목록에서는 쓰지 않는다. 판정은 서버 한 곳
 * ({@code InquiryReplyPolicy})에만 있고, 클라이언트는 {@code canReply}·{@code reason} 을 렌더만 한다.
 *
 * <p>🔴 구매자 연락처·이메일을 담지 않는다(D13).
 */
public record CustomerInquiryResponse(
        Long id,
        String platform,
        Long marketplaceAccountId,
        String accountAlias,
        Long sellerId,
        String sellerName,
        InquiryType inquiryType,
        InquiryStatus status,
        String platformStatus,
        String externalInquiryId,
        String externalOrderId,
        String externalItemId,
        String externalProductId,
        Long productListingId,
        Long orderItemId,
        String itemName,
        String content,
        String category,
        LocalDateTime inquiredAt,
        LocalDateTime answeredAt,
        boolean linked,
        List<CustomerInquiryReplyResponse> replies,
        InquiryRelatedOrderResponse relatedOrder,
        InquiryRelatedListingResponse relatedListing,
        ReplyCapability replyCapability) {
}
