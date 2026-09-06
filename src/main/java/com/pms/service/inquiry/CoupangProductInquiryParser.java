package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿠팡 onlineInquiries(상품문의) 응답의 {@code data.content[]} 1건 → {@link InquiryRecord}
 * (FEATURE_2609_23 / D3).
 *
 * {@link CoupangCallCenterInquiryParser} 의 형제다 — HTTP·DB 를 모르는 순수 클래스이고 같은 정규화
 * 규칙(빈 문자열은 null · 타임스탬프 후보 2개)을 따르지만, 두 응답은 <b>한 필드도 겹치지 않으므로
 * 공통 파서로 묶지 않는다</b>(D3). 한쪽 스키마가 바뀌어도 다른 쪽이 흔들리지 않는 것이 목적이다.
 *
 * <p>상품문의는 구매 전 질문이 다수라 <b>주문이 없는 것이 정상</b>이다(D14·D15).
 * 상담사·고객 식별 정보가 응답에 없으므로 답변 작성자는 전부 판매자다.
 */
@Slf4j
@Component
public class CoupangProductInquiryParser {

    /** 쿠팡 inquiryAt 은 ISO-8601 이지만 공백 구분 포맷도 함께 받아 둔다(클레임 파서와 같은 자세). */
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    private static final String STATUS_ANSWERED = "ANSWERED";
    private static final String STATUS_NO_ANSWER = "NOANSWER";

    /**
     * 상품문의 1건을 파싱한다.
     *
     * @return {@code inquiryAt} 을 파싱할 수 없으면 {@code null}(호출자가 건너뛴다) —
     *         {@code inquired_at} 은 nullable=false 이고 슬라이스(D8)의 기준이라 채울 수 없으면 저장할 수 없다
     */
    public InquiryRecord parse(JsonNode inquiry) {
        LocalDateTime inquiredAt = parseTimestamp(text(inquiry, "inquiryAt"));
        if (inquiredAt == null) {
            log.warn("Skipping product inquiry with unparsable inquiryAt: inquiryId={} inquiryAt={}",
                    text(inquiry, "inquiryId"), text(inquiry, "inquiryAt"));
            return null;
        }

        List<InquiryRecord.ReplyRecord> replies = parseReplies(inquiry.path("commentDtoList"));
        boolean answered = !replies.isEmpty();

        return new InquiryRecord(
                InquiryType.PRODUCT_QNA,
                text(inquiry, "inquiryId"),
                text(inquiry, "vendorItemId"),
                firstOrderId(inquiry),
                text(inquiry, "sellerProductId"),
                null,                                   // itemName 은 응답에 없다 — 셀 연결(D15)이 채운다
                text(inquiry, "content"),
                null,                                   // category 는 고객센터 전용
                answered ? InquiryStatus.ANSWERED : InquiryStatus.UNANSWERED,
                answered ? STATUS_ANSWERED : STATUS_NO_ANSWER,
                inquiredAt,
                earliestReplyAt(replies),
                replies);
    }

    /**
     * 주문 연결은 1건만 저장한다(D14). 2건 이상이면 <b>경고를 남기고</b> 첫 값만 쓴다 —
     * 우측 주문 패널을 N건으로 여는 것은 화면·조회가 함께 복잡해져서 실제 분포를 본 뒤로 미룬다.
     */
    private String firstOrderId(JsonNode inquiry) {
        JsonNode orderIds = inquiry.path("orderIds");
        if (!orderIds.isArray() || orderIds.isEmpty()) {
            return null;
        }
        if (orderIds.size() >= 2) {
            log.warn("Product inquiry has {} orderIds, keeping the first only (D14): inquiryId={}",
                    orderIds.size(), text(inquiry, "inquiryId"));
        }
        return blankToNull(orderIds.path(0).asText());
    }

    /** {@code commentDtoList} → 답변. 상품문의 답변은 전부 판매자이고 상담사·이관 상태가 없다. */
    private List<InquiryRecord.ReplyRecord> parseReplies(JsonNode comments) {
        List<InquiryRecord.ReplyRecord> replies = new ArrayList<>();
        for (JsonNode comment : comments) {
            replies.add(new InquiryRecord.ReplyRecord(
                    text(comment, "inquiryCommentId"),
                    null,                               // parentAnswerId 는 고객센터 전용
                    InquiryAuthorRole.SELLER,
                    null,                               // 작성자 이름이 응답에 없다
                    text(comment, "content"),
                    null,                               // partnerTransferStatus 는 고객센터 전용
                    parseTimestamp(text(comment, "inquiryCommentAt"))));
        }
        return replies;
    }

    /** 답변일 = 가장 <b>이른</b> 답변 시각(최초 응답 시점). 답변이 없으면 null. */
    private LocalDateTime earliestReplyAt(List<InquiryRecord.ReplyRecord> replies) {
        return replies.stream()
                .map(InquiryRecord.ReplyRecord::repliedAt)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /** 필드 부재·null·빈 문자열을 모두 null 로 정규화한다(빈 문자열을 저장하면 "없음"과 구분이 사라진다). */
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

    /** 포맷 후보를 순서대로 시도한다. 전부 실패하면 null. */
    private LocalDateTime parseTimestamp(String raw) {
        if (raw == null) {
            return null;
        }
        for (DateTimeFormatter format : TIMESTAMP_FORMATS) {
            try {
                return LocalDateTime.parse(raw, format);
            } catch (Exception ignored) {
                // 다음 후보로
            }
        }
        return null;
    }
}
