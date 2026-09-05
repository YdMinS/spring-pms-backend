package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.ClaimStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿠팡 returnRequests 응답의 receipt 1건 → {@link ClaimRecord} 목록 (FEATURE_2609_18 / PLAN D2).
 *
 * HTTP·DB 를 모르는 순수 클래스다 — 응답을 읽고 값만 만든다. 교환은 응답 스키마가 한 필드도 겹치지
 * 않으므로 별도 파서(06 {@code CoupangExchangeClaimParser})가 나란히 선다. 공통 파서로 묶지 말 것.
 *
 * <p>⚠️ {@code receiptType} 이 {@code RETURN} 이 아닌 건은 <b>적재하지 않는다</b>(D23) — 단순 결제취소는
 * 처리할 반품이 아니고, 화면에 섞이면 실제 반품이 묻힌다. 그 건의 {@code cancel_count} 보정은
 * {@link com.pms.service.coupang.CoupangReturnSyncServiceImpl} 이 기존대로 처리한다.
 *
 * <p>⚠️ PII 는 {@code requesterName} 만 읽는다(D19) — 연락처·회수 주소 필드는 파싱조차 하지 않는다.
 */
@Slf4j
@Component
public class CoupangReturnClaimParser {

    private static final String RETURN_RECEIPT_TYPE = "RETURN";

    /** 쿠팡 createdAt 실측 포맷 미확정 — 순서대로 시도한다(실응답 확인 전까지 하나로 확정하지 않는다). */
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    /**
     * receipt 1건을 returnItems 개수만큼의 {@link ClaimRecord} 로 편다(receipt 레벨 값은 전부 복제).
     *
     * @return RETURN 이 아니거나 파싱 불가한 건은 빈 리스트 / 개별 item 실패는 그 item 만 제외
     */
    public List<ClaimRecord> parse(JsonNode receipt) {
        String receiptType = text(receipt, "receiptType");
        if (!RETURN_RECEIPT_TYPE.equals(receiptType)) {
            log.debug("Skipping non-RETURN claim: receiptId={} receiptType={}",
                    text(receipt, "receiptId"), receiptType);
            return List.of();
        }

        LocalDateTime receivedAt = parseTimestamp(text(receipt, "createdAt"));
        if (receivedAt == null) {
            log.warn("Skipping claim with unparsable createdAt: receiptId={} createdAt={}",
                    text(receipt, "receiptId"), text(receipt, "createdAt"));
            return List.of();
        }

        String externalClaimId = text(receipt, "receiptId");
        String externalOrderId = text(receipt, "orderId");
        String platformStatus = text(receipt, "receiptStatus");
        JsonNode firstDelivery = receipt.path("returnDeliveryDtos").path(0);

        List<ClaimRecord> records = new ArrayList<>();
        for (JsonNode item : receipt.path("returnItems")) {
            records.add(new ClaimRecord(
                    externalClaimId,
                    externalOrderId,
                    text(item, "shipmentBoxId"),               // 목록 응답에 없을 수 있다 (D22)
                    text(item, "vendorItemId"),
                    text(item, "vendorItemName"),
                    item.path("cancelCount").asInt(0),
                    ClaimStatus.fromCoupangReturn(platformStatus),
                    platformStatus,
                    null,                                      // collectStatus — 교환 전용
                    text(receipt, "reasonCode"),
                    reasonText(receipt),
                    text(receipt, "faultByType"),
                    integer(receipt, "returnShippingCharge"),
                    text(firstDelivery, "deliveryInvoiceNo"),
                    text(firstDelivery, "deliveryCompanyCode"),
                    null,                                      // reshipInvoiceNo — 교환 전용
                    null,                                      // reshipCarrierCode — 교환 전용
                    text(receipt, "requesterName"),            // D19 — 이름만
                    receivedAt,
                    parseTimestamp(text(receipt, "modifiedAt"))));
        }
        return records;
    }

    /** 취소 사유 = 대분류 + 소분류를 " / " 로 결합(빈 값은 생략, 둘 다 없으면 null). */
    private String reasonText(JsonNode receipt) {
        String category1 = text(receipt, "cancelReasonCategory1");
        String category2 = text(receipt, "cancelReasonCategory2");
        if (category1 == null) {
            return category2;
        }
        return (category2 == null) ? category1 : category1 + " / " + category2;
    }

    /** 필드 부재·null·빈 문자열을 모두 null 로 정규화(빈 문자열을 저장하면 "없음"과 구분이 사라진다). */
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String raw = value.asText();
        return raw.isBlank() ? null : raw;
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return (value.isMissingNode() || value.isNull() || !value.canConvertToInt())
                ? null
                : value.asInt();
    }

    /** 실측 포맷 확정 전까지 후보를 순서대로 시도한다. 전부 실패하면 null(호출자가 건너뛴다). */
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
