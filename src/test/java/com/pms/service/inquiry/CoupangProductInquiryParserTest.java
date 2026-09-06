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
 * CoupangProductInquiryParser — onlineInquiries 의 content[] 1건 → InquiryRecord(순수 단위테스트, 목 없음).
 *
 * fixture 는 쿠팡 문서의 응답 예시 형태를 그대로 문자열 상수로 둔다
 * ({@code CoupangExchangeClaimParserTest} 와 같은 형태).
 */
class CoupangProductInquiryParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CoupangProductInquiryParser parser = new CoupangProductInquiryParser();

    @Test
    void parse_noComment_isUnanswered() {
        JsonNode inquiry = read("""
                {
                  "inquiryId": 3001,
                  "vendorItemId": 700001,
                  "sellerProductId": 900001,
                  "orderIds": [],
                  "content": "재입고 예정이 있나요?",
                  "inquiryAt": "2026-09-01T10:20:30",
                  "commentDtoList": []
                }
                """);

        InquiryRecord record = parser.parse(inquiry);

        assertThat(record.type()).isEqualTo(InquiryType.PRODUCT_QNA);
        assertThat(record.externalInquiryId()).isEqualTo("3001");
        assertThat(record.externalItemId()).isEqualTo("700001");
        assertThat(record.externalProductId()).isEqualTo("900001");
        assertThat(record.externalOrderId()).isNull();          // 주문 없는 질문이 정상이다 (D14·D15)
        assertThat(record.itemName()).isNull();                 // 응답에 없다 — 셀 연결이 채운다
        assertThat(record.category()).isNull();                 // 고객센터 전용
        assertThat(record.status()).isEqualTo(InquiryStatus.UNANSWERED);
        assertThat(record.platformStatus()).isEqualTo("NOANSWER");
        assertThat(record.answeredAt()).isNull();
        assertThat(record.replies()).isEmpty();
        assertThat(record.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 20, 30));
    }

    @Test
    void parse_withComments_isAnsweredAndUsesEarliestCommentAsAnsweredAt() {
        JsonNode inquiry = read("""
                {
                  "inquiryId": 3002,
                  "vendorItemId": 700002,
                  "orderIds": ["O-1"],
                  "content": "언제 배송되나요?",
                  "inquiryAt": "2026-09-02T09:00:00",
                  "commentDtoList": [
                    {"inquiryCommentId": 5002, "content": "추가 안내드립니다.", "inquiryCommentAt": "2026-09-02T15:00:00"},
                    {"inquiryCommentId": 5001, "content": "내일 출고 예정입니다.", "inquiryCommentAt": "2026-09-02T11:30:00"}
                  ]
                }
                """);

        InquiryRecord record = parser.parse(inquiry);

        assertThat(record.status()).isEqualTo(InquiryStatus.ANSWERED);
        assertThat(record.platformStatus()).isEqualTo("ANSWERED");
        // 답변일 = 가장 이른 답변(최초 응답 시점)이지 마지막 답변이 아니다.
        assertThat(record.answeredAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 11, 30, 0));
        assertThat(record.externalOrderId()).isEqualTo("O-1");
        assertThat(record.replies()).hasSize(2);
        assertThat(record.replies()).allSatisfy(reply -> {
            // 상품문의 답변은 전부 판매자이고 상담사·이관 상태가 없다.
            assertThat(reply.authorRole()).isEqualTo(InquiryAuthorRole.SELLER);
            assertThat(reply.authorName()).isNull();
            assertThat(reply.transferStatus()).isNull();
            assertThat(reply.parentExternalReplyId()).isNull();
        });
        assertThat(record.replies().get(0).externalReplyId()).isEqualTo("5002");
        assertThat(record.replies().get(0).repliedAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 15, 0, 0));
    }

    @Test
    void parse_multipleOrderIds_keepsFirstOnly() {
        // D14 — 우측 주문 패널을 N건으로 열지 않는다. 첫 값만 쓰고 경고 로그를 남긴다.
        JsonNode inquiry = read("""
                {
                  "inquiryId": 3003,
                  "vendorItemId": 700003,
                  "orderIds": ["O-1", "O-2", "O-3"],
                  "content": "합배송 되나요?",
                  "inquiryAt": "2026-09-03 08:00:00",
                  "commentDtoList": []
                }
                """);

        InquiryRecord record = parser.parse(inquiry);

        assertThat(record.externalOrderId()).isEqualTo("O-1");
        assertThat(record.inquiredAt()).isEqualTo(LocalDateTime.of(2026, 9, 3, 8, 0, 0));
    }

    @Test
    void parse_unparsableInquiryAt_returnsNull() {
        // inquired_at 은 nullable=false 이고 슬라이스(D8)의 기준이라 채울 수 없으면 저장할 수 없다.
        JsonNode inquiry = read("""
                {"inquiryId": 3004, "content": "?", "inquiryAt": "", "commentDtoList": []}
                """);

        assertThat(parser.parse(inquiry)).isNull();
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
