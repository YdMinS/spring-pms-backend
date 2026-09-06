package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CoupangCallCenterInquiryParser — callCenterInquiries 의 content[] 1건 → InquiryRecord.
 *
 * PLAN §3.1 의 상태 4분기 · answerType→role 매핑 · parentAnswerId 보존 · vendorItemId 배열이
 * 이 파서의 전부다(상품문의와 한 필드도 겹치지 않는다).
 */
class CoupangCallCenterInquiryParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CoupangCallCenterInquiryParser parser = new CoupangCallCenterInquiryParser();

    @Test
    void parse_progressWithRequestAnswer_isUnansweredAndMapsFields() {
        JsonNode inquiry = read("""
                {
                  "inquiryId": 4001,
                  "vendorItemId": [700001, 700002],
                  "itemName": "양말 3켤레",
                  "orderId": "O-1",
                  "content": "배송이 지연되고 있습니다.",
                  "receiptCategory": "배송지연",
                  "inquiryStatus": "progress",
                  "csPartnerCounselingStatus": "requestAnswer",
                  "inquiryAt": "2026-09-01T10:00:00",
                  "answeredAt": null,
                  "replies": [
                    {"answerId": 8001, "parentAnswerId": null, "answerType": "csAgent",
                     "receptionistName": "김상담", "content": "판매자 확인 요청드립니다.",
                     "partnerTransferStatus": "requestAnswer", "replyAt": "2026-09-01T10:30:00"}
                  ]
                }
                """);

        InquiryRecord record = parser.parse(inquiry);

        assertThat(record.type()).isEqualTo(InquiryType.CALL_CENTER);
        assertThat(record.externalInquiryId()).isEqualTo("4001");
        // ⚠️ 고객센터의 vendorItemId 는 배열이다 — 첫 값만 쓴다.
        assertThat(record.externalItemId()).isEqualTo("700001");
        assertThat(record.itemName()).isEqualTo("양말 3켤레");
        assertThat(record.externalOrderId()).isEqualTo("O-1");
        assertThat(record.category()).isEqualTo("배송지연");
        assertThat(record.externalProductId()).isNull();          // 상품문의 전용
        assertThat(record.status()).isEqualTo(InquiryStatus.UNANSWERED);
        assertThat(record.platformStatus()).isEqualTo("progress/requestAnswer");
        assertThat(record.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 0, 0));
        assertThat(record.answeredAt()).isNull();

        assertThat(record.replies()).hasSize(1);
        InquiryRecord.ReplyRecord reply = record.replies().get(0);
        assertThat(reply.externalReplyId()).isEqualTo("8001");
        assertThat(reply.authorRole()).isEqualTo(InquiryAuthorRole.CS_AGENT);
        assertThat(reply.authorName()).isEqualTo("김상담");        // 상담사 이름은 고객 PII 가 아니다
        assertThat(reply.transferStatus()).isEqualTo("requestAnswer");
        assertThat(reply.repliedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 30, 0));
    }

    @Test
    void parse_counselingAnswered_isAnsweredAndPreservesParentAnswerId() {
        JsonNode inquiry = read("""
                {
                  "inquiryId": 4002,
                  "vendorItemId": [700003],
                  "orderId": "O-2",
                  "content": "재고 문의",
                  "inquiryStatus": "progress",
                  "csPartnerCounselingStatus": "answered",
                  "inquiryAt": "2026-09-02T09:00:00",
                  "answeredAt": "2026-09-02T12:00:00",
                  "replies": [
                    {"answerId": 8002, "parentAnswerId": 8001, "answerType": "vendor",
                     "receptionistName": null, "content": "재고 있습니다.",
                     "partnerTransferStatus": "answer", "replyAt": "2026-09-02T12:00:00"}
                  ]
                }
                """);

        InquiryRecord record = parser.parse(inquiry);

        assertThat(record.status()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(record.platformStatus()).isEqualTo("progress/answered");
        assertThat(record.answeredAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 12, 0, 0));
        assertThat(record.replies().get(0).authorRole()).isEqualTo(InquiryAuthorRole.SELLER);
        // 04 의 답변 전송이 쓰는 값이라 조회 단계에서 반드시 보존한다.
        assertThat(record.replies().get(0).parentExternalReplyId()).isEqualTo("8001");
    }

    @Test
    void parse_complete_isClosedEvenWhenCounselingSaysAnswered() {
        // 판정 순서가 뒤집히면 종결 건이 ANSWERED 로 남는다 — 순서를 고정하는 테스트다.
        JsonNode inquiry = read("""
                {
                  "inquiryId": 4003,
                  "vendorItemId": [700004],
                  "content": "종결 건",
                  "inquiryStatus": "complete",
                  "csPartnerCounselingStatus": "answered",
                  "inquiryAt": "2026-09-03T09:00:00",
                  "replies": []
                }
                """);

        InquiryRecord record = parser.parse(inquiry);

        assertThat(record.status()).isEqualTo(InquiryStatus.CLOSED);
        assertThat(record.platformStatus()).isEqualTo("complete/answered");
    }

    @Test
    void parse_progressWithoutRequestAnswer_fallsBackToAnswered() {
        // 어느 분기에도 걸리지 않는 조합 — 조용히 떨어지지 않도록 ANSWERED 로 두고 원문을 남긴다.
        JsonNode inquiry = read("""
                {
                  "inquiryId": 4004,
                  "vendorItemId": [700005],
                  "content": "이관만 된 건",
                  "inquiryStatus": "progress",
                  "csPartnerCounselingStatus": "transfer",
                  "inquiryAt": "2026-09-04T09:00:00",
                  "replies": [
                    {"answerId": 8004, "answerType": "csAgent", "content": "확인 중",
                     "partnerTransferStatus": "transfer", "replyAt": "2026-09-04T09:30:00"}
                  ]
                }
                """);

        InquiryRecord record = parser.parse(inquiry);

        assertThat(record.status()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(record.platformStatus()).isEqualTo("progress/transfer");
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
