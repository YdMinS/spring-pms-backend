package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.ClaimStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CoupangReturnClaimParser — receipt JSON → ClaimRecord 변환(순수 단위테스트, 목 없음).
 * receiptType 게이트(D23)·shipmentBoxId 부재(D22)·createdAt 파싱 실패가 핵심 분기다.
 */
class CoupangReturnClaimParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CoupangReturnClaimParser parser = new CoupangReturnClaimParser();

    @Test
    void parse_returnReceiptWithTwoItems_copiesReceiptLevelFieldsToEach() {
        // receiptStatus=PR 은 PENDING_REVIEW 로 정규화된다(PLAN §3.1 매핑 고정)
        JsonNode receipt = read("""
                {
                  "receiptId": 1001,
                  "orderId": "O-1",
                  "receiptType": "RETURN",
                  "receiptStatus": "PR",
                  "createdAt": "2026-09-01T10:20:30",
                  "modifiedAt": "2026-09-02T11:00:00",
                  "requesterName": "홍길동",
                  "reasonCode": "CHANGEMIND",
                  "cancelReasonCategory1": "단순변심",
                  "cancelReasonCategory2": "필요없어짐",
                  "faultByType": "CUSTOMER",
                  "returnShippingCharge": 3000,
                  "returnDeliveryDtos": [{"deliveryInvoiceNo": "INV-9", "deliveryCompanyCode": "CJGLS"}],
                  "returnItems": [
                    {"shipmentBoxId": "B-1", "vendorItemId": "V-1", "vendorItemName": "양말", "cancelCount": 2},
                    {"shipmentBoxId": "B-1", "vendorItemId": "V-2", "vendorItemName": "장갑", "cancelCount": 1}
                  ]
                }
                """);

        List<ClaimRecord> records = parser.parse(receipt);

        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.externalClaimId()).isEqualTo("1001");
            assertThat(r.externalOrderId()).isEqualTo("O-1");
            assertThat(r.status()).isEqualTo(ClaimStatus.PENDING_REVIEW);
            assertThat(r.platformStatus()).isEqualTo("PR");
            assertThat(r.reasonCode()).isEqualTo("CHANGEMIND");
            assertThat(r.reasonText()).isEqualTo("단순변심 / 필요없어짐");
            assertThat(r.faultType()).isEqualTo("CUSTOMER");
            assertThat(r.returnShippingCharge()).isEqualTo(3000);
            assertThat(r.collectInvoiceNo()).isEqualTo("INV-9");
            assertThat(r.collectCarrierCode()).isEqualTo("CJGLS");
            assertThat(r.requesterName()).isEqualTo("홍길동");
            assertThat(r.receivedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 20, 30));
            assertThat(r.platformModifiedAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 11, 0, 0));
        });
        assertThat(records.get(0).externalItemId()).isEqualTo("V-1");
        assertThat(records.get(0).itemName()).isEqualTo("양말");
        assertThat(records.get(0).quantity()).isEqualTo(2);
        assertThat(records.get(1).externalItemId()).isEqualTo("V-2");
        assertThat(records.get(1).quantity()).isEqualTo(1);
    }

    @Test
    void parse_cancelReceiptType_returnsEmpty() {
        // D23: 단순 결제취소는 처리할 반품이 아니다 → 적재하지 않는다(cancel_count 보정은 기존 경로가 한다)
        JsonNode receipt = read("""
                {
                  "receiptId": 2002,
                  "orderId": "O-2",
                  "receiptType": "CANCEL",
                  "receiptStatus": "UC",
                  "createdAt": "2026-09-01T10:20:30",
                  "returnItems": [{"vendorItemId": "V-1", "cancelCount": 1}]
                }
                """);

        assertThat(parser.parse(receipt)).isEmpty();
    }

    @Test
    void parse_missingShipmentBoxId_keepsBoxIdNullAndParsesRest() {
        // D22: 목록 응답에 shipmentBoxId 가 없을 수 있다 → 나머지는 정상 파싱하고 boxId 만 null
        JsonNode receipt = read("""
                {
                  "receiptId": 3003,
                  "orderId": "O-3",
                  "receiptType": "RETURN",
                  "receiptStatus": "UC",
                  "createdAt": "2026-09-01 10:20:30",
                  "returnItems": [{"vendorItemId": "V-9", "vendorItemName": "모자", "cancelCount": 1}]
                }
                """);

        List<ClaimRecord> records = parser.parse(receipt);

        assertThat(records).hasSize(1);
        ClaimRecord record = records.get(0);
        assertThat(record.externalBoxId()).isNull();
        assertThat(record.externalItemId()).isEqualTo("V-9");
        assertThat(record.status()).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(record.receivedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 20, 30));
        assertThat(record.returnShippingCharge()).isNull();
        assertThat(record.collectInvoiceNo()).isNull();
    }

    @Test
    void parse_unparsableCreatedAt_skipsReceiptWithoutThrowing() {
        // receivedAt 은 nullable=false 라 저장할 수 없다 → 그 건만 건너뛴다(예외 없음)
        JsonNode receipt = read("""
                {
                  "receiptId": 4004,
                  "orderId": "O-4",
                  "receiptType": "RETURN",
                  "receiptStatus": "CC",
                  "createdAt": "01/09/2026 오전 10시",
                  "returnItems": [{"vendorItemId": "V-1", "cancelCount": 1}]
                }
                """);

        assertThat(parser.parse(receipt)).isEmpty();
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
