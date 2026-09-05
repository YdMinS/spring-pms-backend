package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.ClaimStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CoupangExchangeClaimParser — 교환 receipt JSON → ClaimRecord(순수 단위테스트, 목 없음).
 *
 * 스키마가 실계정 미검증이라 <b>alias 폴백을 고정하는 것</b>이 이 테스트의 핵심이다.
 * 반품과 달리 receiptType 게이트는 없다 — 교환 API 응답은 전부 교환이다.
 */
class CoupangExchangeClaimParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CoupangExchangeClaimParser parser = new CoupangExchangeClaimParser();

    @Test
    void parse_receiptWithTwoItems_copiesReceiptLevelFieldsAndMapsBothInvoices() {
        JsonNode receipt = read("""
                {
                  "exchangeId": 5001,
                  "orderId": "O-1",
                  "receiptStatus": "PROGRESS",
                  "createdAt": "2026-09-01T10:20:30",
                  "modifiedAt": "2026-09-02T11:00:00",
                  "requesterName": "홍길동",
                  "reasonCode": "DEFECT",
                  "reasonCodeText": "상품 불량",
                  "faultByType": "VENDOR",
                  "collectDeliveryDtos": [{"deliveryInvoiceNo": "COL-1", "deliveryCompanyCode": "CJGLS"}],
                  "exchangeDeliveryDtos": [{"deliveryInvoiceNo": "RES-1", "deliveryCompanyCode": "HANJIN"}],
                  "exchangeItemDtoV1s": [
                    {"shipmentBoxId": "B-1", "vendorItemId": "V-1", "vendorItemName": "양말", "quantity": 2},
                    {"shipmentBoxId": "B-1", "vendorItemId": "V-2", "vendorItemName": "장갑", "quantity": 1}
                  ]
                }
                """);

        List<ClaimRecord> records = parser.parse(receipt);

        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.externalClaimId()).isEqualTo("5001");
            assertThat(r.externalOrderId()).isEqualTo("O-1");
            assertThat(r.status()).isEqualTo(ClaimStatus.IN_PROGRESS);
            assertThat(r.platformStatus()).isEqualTo("PROGRESS");     // 원문 그대로 (D3)
            assertThat(r.reasonCode()).isEqualTo("DEFECT");
            assertThat(r.reasonText()).isEqualTo("상품 불량");
            assertThat(r.faultType()).isEqualTo("VENDOR");
            assertThat(r.requesterName()).isEqualTo("홍길동");
            // 회수(고객→판매자)와 재발송(판매자→고객)은 방향이 다른 송장이다 — 섞이면 안 된다
            assertThat(r.collectInvoiceNo()).isEqualTo("COL-1");
            assertThat(r.collectCarrierCode()).isEqualTo("CJGLS");
            assertThat(r.reshipInvoiceNo()).isEqualTo("RES-1");
            assertThat(r.reshipCarrierCode()).isEqualTo("HANJIN");
            assertThat(r.returnShippingCharge()).isNull();            // 반품 전용 컬럼
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
    void parse_aliasFieldsOnly_producesSameResult() {
        // 스키마 미검증분을 alias 로 흡수하는지 고정한다 — 1순위 경로가 없어도 결과가 같아야 한다.
        JsonNode receipt = read("""
                {
                  "exchangeId": 5002,
                  "orderId": "O-2",
                  "status": "SUCCESS",
                  "createdAt": "2026-09-01 09:00:00",
                  "reasonCode": "DEFECT",
                  "returnDeliveryDtos": [{"deliveryInvoiceNo": "COL-2", "deliveryCompanyCode": "CJGLS"}],
                  "deliveryDtos": [{"deliveryInvoiceNo": "RES-2", "deliveryCompanyCode": "HANJIN"}],
                  "exchangeItemDtoV1s": [
                    {"shipmentBoxId": "B-2", "vendorItemId": "V-3", "vendorItemName": "모자", "cancelCount": 3}
                  ]
                }
                """);

        List<ClaimRecord> records = parser.parse(receipt);

        assertThat(records).hasSize(1);
        ClaimRecord r = records.get(0);
        assertThat(r.platformStatus()).isEqualTo("SUCCESS");
        assertThat(r.status()).isEqualTo(ClaimStatus.DONE);
        assertThat(r.reasonText()).isEqualTo("DEFECT");               // reasonCodeText 부재 → reasonCode
        assertThat(r.collectInvoiceNo()).isEqualTo("COL-2");
        assertThat(r.reshipInvoiceNo()).isEqualTo("RES-2");
        assertThat(r.quantity()).isEqualTo(3);                        // cancelCount 폴백
        assertThat(r.receivedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0, 0));
    }

    @Test
    void parse_missingBoxIdAndQuantity_keepsRecordWithDefaults() {
        // boxId 부재는 D22 2단 매칭이 흡수한다 / 수량 부재는 1 — 교환은 최소 1개다.
        JsonNode receipt = read("""
                {
                  "exchangeId": 5003,
                  "orderId": "O-3",
                  "receiptStatus": "RECEIPT",
                  "createdAt": "2026-09-01T10:00:00",
                  "exchangeItemDtoV1s": [{"vendorItemId": "V-4", "vendorItemName": "신발"}]
                }
                """);

        List<ClaimRecord> records = parser.parse(receipt);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).externalBoxId()).isNull();
        assertThat(records.get(0).quantity()).isEqualTo(1);
        assertThat(records.get(0).status()).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(records.get(0).collectInvoiceNo()).isNull();
        assertThat(records.get(0).reshipInvoiceNo()).isNull();
    }

    @Test
    void parse_unparsableCreatedAt_returnsEmptyWithoutThrowing() {
        // receivedAt 은 nullable=false 라 채울 수 없으면 저장할 수 없다 — 반품과 같은 판단.
        JsonNode receipt = read("""
                {
                  "exchangeId": 5004,
                  "orderId": "O-4",
                  "receiptStatus": "RECEIPT",
                  "createdAt": "2026/09/01",
                  "exchangeItemDtoV1s": [{"vendorItemId": "V-5", "quantity": 1}]
                }
                """);

        assertThat(parser.parse(receipt)).isEmpty();
    }

    @Test
    void parse_noItems_returnsEmpty() {
        JsonNode receipt = read("""
                {"exchangeId": 5005, "orderId": "O-5", "createdAt": "2026-09-01T10:00:00"}
                """);

        assertThat(parser.parse(receipt)).isEmpty();
    }

    @Test
    void fromCoupangExchange_normalizesKnownCodes_andTreatsUnknownAsOpen() {
        // 모르는 값을 종결로 오분류하면 추적 대상(D7)에서 빠져 영영 갱신되지 않는다.
        assertThat(ClaimStatus.fromCoupangExchange("RECEIPT")).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(ClaimStatus.fromCoupangExchange("PROGRESS")).isEqualTo(ClaimStatus.IN_PROGRESS);
        assertThat(ClaimStatus.fromCoupangExchange("SUCCESS")).isEqualTo(ClaimStatus.DONE);
        assertThat(ClaimStatus.fromCoupangExchange("REJECT")).isEqualTo(ClaimStatus.REJECTED);
        assertThat(ClaimStatus.fromCoupangExchange("CANCEL")).isEqualTo(ClaimStatus.WITHDRAWN);
        assertThat(ClaimStatus.fromCoupangExchange("NEW_CODE_2027")).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(ClaimStatus.fromCoupangExchange(null)).isEqualTo(ClaimStatus.RECEIVED);

        // 교환 추적의 종료 조건 — REJECTED·WITHDRAWN 은 이미 종결로 정의돼 있다
        assertThat(ClaimStatus.closedStatuses())
                .contains(ClaimStatus.REJECTED, ClaimStatus.WITHDRAWN);
    }

    @Test
    void parse_collectStatus_prefersTheFirstAliasAndKeepsTheRawValue() {
        // 회수상태는 교환 액션(05)의 가능 조건이라 원문 그대로 보존해야 한다 — 정규화하면
        // 액션 판정이 축약된 값 위에서 돌게 된다.
        JsonNode both = read("""
                {"exchangeId": 5010, "orderId": "O-10", "createdAt": "2026-09-01T10:00:00",
                 "collectStatus": "CompleteCollect", "returnDeliveryStatus": "BeforeDirection",
                 "exchangeItemDtoV1s": [{"vendorItemId": "V-1"}]}
                """);
        assertThat(parser.parse(both).get(0).collectStatus()).isEqualTo("CompleteCollect");

        JsonNode aliasOnly = read("""
                {"exchangeId": 5011, "orderId": "O-11", "createdAt": "2026-09-01T10:00:00",
                 "returnDeliveryStatus": "BeforeDirection",
                 "exchangeItemDtoV1s": [{"vendorItemId": "V-1"}]}
                """);
        assertThat(parser.parse(aliasOnly).get(0).collectStatus()).isEqualTo("BeforeDirection");
    }

    @Test
    void parse_collectStatusMissing_yieldsNullInsteadOfThrowing() {
        // 값이 없으면 액션을 열지 않는다(D3). 비어 있는 것과 조건을 만족하는 것은 다르다.
        JsonNode receipt = read("""
                {"exchangeId": 5012, "orderId": "O-12", "createdAt": "2026-09-01T10:00:00",
                 "exchangeItemDtoV1s": [{"vendorItemId": "V-1"}]}
                """);

        assertThat(parser.parse(receipt).get(0).collectStatus()).isNull();
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
