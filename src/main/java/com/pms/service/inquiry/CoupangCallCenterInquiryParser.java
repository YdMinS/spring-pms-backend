package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.service.coupang.CoupangTimestamps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿠팡 callCenterInquiries(고객센터문의) 응답의 {@code data.content[]} 1건 → {@link InquiryRecord}
 * (FEATURE_2609_23 / D3).
 *
 * {@link CoupangProductInquiryParser} 의 형제지만 응답 스키마가 한 필드도 겹치지 않아 공통 파서로
 * 묶지 않는다(D3). 이쪽만 갖는 것: 상담사 답변({@code csAgent})·이관 상태
 * ({@code partnerTransferStatus})·접수 분류({@code receiptCategory})·{@code parentAnswerId}.
 *
 * <p>🔴 응답에는 구매자 연락처({@code buyerPhone}·{@code buyerEmail})가 실려 오지만 <b>파싱조차 하지
 * 않는다</b>(D13). {@code receptionistName} 은 쿠팡 상담사 이름이라 고객 PII 가 아니고 저장한다.
 */
@Slf4j
@Component
public class CoupangCallCenterInquiryParser {

    private static final String INQUIRY_STATUS_COMPLETE = "complete";
    private static final String INQUIRY_STATUS_PROGRESS = "progress";
    private static final String COUNSELING_STATUS_ANSWERED = "answered";
    private static final String TRANSFER_STATUS_REQUEST_ANSWER = "requestAnswer";
    private static final String ANSWER_TYPE_CS_AGENT = "csAgent";

    /**
     * 고객센터문의 1건을 파싱한다.
     *
     * @return {@code inquiryAt} 을 파싱할 수 없으면 {@code null}(호출자가 건너뛴다)
     */
    public InquiryRecord parse(JsonNode inquiry) {
        LocalDateTime inquiredAt = CoupangTimestamps.parse(text(inquiry, "inquiryAt"));
        if (inquiredAt == null) {
            log.warn("Skipping call-center inquiry with unparsable inquiryAt: inquiryId={} inquiryAt={}",
                    text(inquiry, "inquiryId"), text(inquiry, "inquiryAt"));
            return null;
        }

        String inquiryStatus = text(inquiry, "inquiryStatus");
        String counselingStatus = text(inquiry, "csPartnerCounselingStatus");
        List<InquiryRecord.ReplyRecord> replies = parseReplies(inquiry.path("replies"));

        return new InquiryRecord(
                InquiryType.CALL_CENTER,
                text(inquiry, "inquiryId"),
                firstVendorItemId(inquiry),
                text(inquiry, "orderId"),
                null,                                   // sellerProductId 는 상품문의 전용
                text(inquiry, "itemName"),
                text(inquiry, "content"),
                text(inquiry, "receiptCategory"),
                status(inquiryStatus, counselingStatus, replies, inquiry),
                platformStatus(inquiryStatus, counselingStatus),   // 원문 이원 저장 (D7)
                inquiredAt,
                CoupangTimestamps.parse(text(inquiry, "answeredAt")),
                replies);
    }

    /**
     * 원문 상태 보존 형식 {@code {inquiryStatus}/{csPartnerCounselingStatus}} (PLAN §3.1).
     * 컬럼이 nullable=false 라 없는 쪽은 빈 문자열로 둔다("null" 이라는 문자열을 저장하지 않는다).
     */
    private String platformStatus(String inquiryStatus, String counselingStatus) {
        return (inquiryStatus == null ? "" : inquiryStatus) + "/"
                + (counselingStatus == null ? "" : counselingStatus);
    }

    /**
     * 상태 정규화 (PLAN §3.1). 판정 순서를 바꾸지 말 것 — {@code complete} 인데 상담 상태가
     * {@code answered} 인 건이 있어서, 종결 판정이 먼저여야 한다.
     *
     * <p>어느 분기에도 걸리지 않으면 {@code ANSWERED} 로 두되 원문을 debug 로 남긴다 — 조용히
     * 떨어지면 새 상태 코드가 들어온 것을 아무도 모른다.
     */
    private InquiryStatus status(String inquiryStatus, String counselingStatus,
                                 List<InquiryRecord.ReplyRecord> replies, JsonNode inquiry) {
        if (INQUIRY_STATUS_COMPLETE.equalsIgnoreCase(inquiryStatus)) {
            return InquiryStatus.CLOSED;
        }
        if (COUNSELING_STATUS_ANSWERED.equalsIgnoreCase(counselingStatus)) {
            return InquiryStatus.ANSWERED;
        }
        if (INQUIRY_STATUS_PROGRESS.equalsIgnoreCase(inquiryStatus) && awaitingOurAnswer(replies)) {
            return InquiryStatus.UNANSWERED;
        }
        log.debug("Unmapped call-center inquiry status: inquiryId={} inquiryStatus={} csPartnerCounselingStatus={}",
                text(inquiry, "inquiryId"), inquiryStatus, counselingStatus);
        return InquiryStatus.ANSWERED;
    }

    /** {@code partnerTransferStatus = requestAnswer} 가 곧 "우리가 답할 차례" 라는 신호다. */
    private boolean awaitingOurAnswer(List<InquiryRecord.ReplyRecord> replies) {
        return replies.stream()
                .anyMatch(reply -> TRANSFER_STATUS_REQUEST_ANSWER.equalsIgnoreCase(reply.transferStatus()));
    }

    /** ⚠️ 고객센터의 {@code vendorItemId} 는 <b>배열</b>이다(상품문의는 단일 값). 첫 값만 쓴다. */
    private String firstVendorItemId(JsonNode inquiry) {
        JsonNode vendorItemIds = inquiry.path("vendorItemId");
        if (vendorItemIds.isArray()) {
            return vendorItemIds.isEmpty() ? null : blankToNull(vendorItemIds.path(0).asText());
        }
        return text(inquiry, "vendorItemId");
    }

    private List<InquiryRecord.ReplyRecord> parseReplies(JsonNode replies) {
        List<InquiryRecord.ReplyRecord> parsed = new ArrayList<>();
        for (JsonNode reply : replies) {
            parsed.add(new InquiryRecord.ReplyRecord(
                    text(reply, "answerId"),
                    text(reply, "parentAnswerId"),      // 04 의 답변 전송이 쓰는 값 — 반드시 보존
                    authorRole(text(reply, "answerType")),
                    text(reply, "receptionistName"),    // 상담사 이름 (고객 PII 아님)
                    text(reply, "content"),
                    text(reply, "partnerTransferStatus"),
                    CoupangTimestamps.parse(text(reply, "replyAt"))));
        }
        return parsed;
    }

    /** {@code csAgent} → 상담사, 그 외({@code vendor} 포함) → 판매자. */
    private InquiryAuthorRole authorRole(String answerType) {
        return ANSWER_TYPE_CS_AGENT.equalsIgnoreCase(answerType)
                ? InquiryAuthorRole.CS_AGENT
                : InquiryAuthorRole.SELLER;
    }

    /** 필드 부재·null·빈 문자열을 모두 null 로 정규화한다. */
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return blankToNull(value.asText());
    }

    private String blankToNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw;
    }
}
