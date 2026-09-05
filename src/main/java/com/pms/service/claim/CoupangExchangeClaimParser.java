package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.ClaimStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 쿠팡 exchangeRequests 응답의 receipt 1건 → {@link ClaimRecord} 목록 (FEATURE_2609_18 / PLAN D2).
 *
 * {@link CoupangReturnClaimParser} 의 형제다 — HTTP·DB 를 모르는 순수 클래스이고 같은 정규화 규칙
 * (빈 문자열은 null · 타임스탬프 후보 2개)을 따르지만, 응답 스키마가 겹치지 않으므로 <b>공통 파서로
 * 묶지 않는다</b>(D2). 한쪽 스키마가 바뀌어도 다른 쪽이 흔들리지 않는 것이 목적이다.
 *
 * <p>⚠️ 교환 응답 스키마는 <b>실계정 미검증</b>이다(문서만 확인). 불확실한 경로는 alias 후보 목록으로
 * 흡수하고 dev 확인으로 1순위를 확정한다 — 추측한 경로를 확정된 것처럼 단일 하드코딩하지 말 것.
 *
 * <p>⚠️ 반품의 {@code receiptType} 게이트(D23) 같은 것은 <b>없다</b> — 교환 API 응답은 전부 교환이다.
 *
 * <p>⚠️ PII 는 {@code requesterName} 만 읽는다(D19) — 연락처·회수 주소 필드는 파싱조차 하지 않는다.
 */
@Slf4j
@Component
public class CoupangExchangeClaimParser {

    /** 교환은 최소 1개다 — 수량을 못 읽었을 때 0 을 넣으면 화면 수량이 무의미해진다. */
    private static final int DEFAULT_QUANTITY = 1;

    /** 쿠팡 createdAt 실측 포맷 미확정 — 반품 파서와 같은 후보를 같은 순서로 시도한다. */
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    /**
     * receipt 1건을 교환 아이템 개수만큼의 {@link ClaimRecord} 로 편다(receipt 레벨 값은 전부 복제).
     *
     * @return {@code createdAt} 을 파싱할 수 없거나 아이템이 없으면 빈 리스트
     */
    public List<ClaimRecord> parse(JsonNode receipt) {
        JsonNode items = firstPresent(receipt, "exchangeItemDtoV1s");
        logFieldNames(receipt, items.path(0));

        LocalDateTime receivedAt = parseTimestamp(firstText(receipt, "createdAt"));
        if (receivedAt == null) {
            // receivedAt 은 nullable=false 라 채울 수 없으면 저장할 수 없다 — 반품과 같은 판단.
            log.warn("Skipping exchange claim with unparsable createdAt: exchangeId={} createdAt={}",
                    firstText(receipt, "exchangeId"), firstText(receipt, "createdAt"));
            return List.of();
        }

        String externalClaimId = firstText(receipt, "exchangeId");
        String externalOrderId = firstText(receipt, "orderId");
        String platformStatus = firstText(receipt, "receiptStatus", "status");
        // 회수상태 — 교환 액션(05)의 가능 조건이다. 원문 그대로 저장한다(정규화 금지).
        // 스키마 미검증이라 alias 로 받는다: 1순위가 없으면 2순위, 둘 다 없으면 null(= 액션 없음, D3).
        String collectStatus = firstText(receipt, "collectStatus", "returnDeliveryStatus");
        // 회수(고객→판매자) / 재발송(판매자→고객) — 방향이 다른 두 송장이라 섞으면 안 된다.
        JsonNode collectDelivery = firstPresent(receipt, "collectDeliveryDtos", "returnDeliveryDtos").path(0);
        JsonNode reshipDelivery = firstPresent(receipt, "exchangeDeliveryDtos", "deliveryDtos").path(0);

        List<ClaimRecord> records = new ArrayList<>();
        for (JsonNode item : items) {
            records.add(new ClaimRecord(
                    externalClaimId,
                    externalOrderId,
                    firstText(item, "shipmentBoxId"),           // 없으면 null — D22 2단 매칭이 흡수한다
                    firstText(item, "vendorItemId"),
                    firstText(item, "vendorItemName"),
                    quantity(item),
                    ClaimStatus.fromCoupangExchange(platformStatus),
                    platformStatus,                             // 원문 그대로 보존 (D3)
                    collectStatus,                              // 원문 그대로 보존 (D3)
                    firstText(receipt, "reasonCode"),
                    firstText(receipt, "reasonCodeText", "reasonCode"),
                    firstText(receipt, "faultByType"),          // 반품과 같은 키
                    null,                                       // returnShippingCharge — 반품 전용
                    firstText(collectDelivery, "deliveryInvoiceNo"),
                    firstText(collectDelivery, "deliveryCompanyCode"),
                    firstText(reshipDelivery, "deliveryInvoiceNo"),
                    firstText(reshipDelivery, "deliveryCompanyCode"),
                    firstText(receipt, "requesterName"),        // D19 — 이름만
                    receivedAt,
                    parseTimestamp(firstText(receipt, "modifiedAt"))));
        }
        return records;
    }

    /** 교환 수량. 후보 둘 다 없으면 1 — 0 이면 화면 수량이 무의미해진다. */
    private int quantity(JsonNode item) {
        for (String field : List.of("quantity", "cancelCount")) {
            JsonNode value = item.path(field);
            if (!value.isMissingNode() && !value.isNull() && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return DEFAULT_QUANTITY;
    }

    /**
     * 실측용 로그 — <b>필드 이름만</b> 찍는다(값은 PII 를 포함할 수 있다, D19).
     * dev 에서 이 줄을 보고 alias 의 1순위를 확정한다.
     */
    private void logFieldNames(JsonNode receipt, JsonNode firstItem) {
        if (log.isDebugEnabled()) {
            log.debug("Exchange receipt fields: {} / item fields: {}", fieldNames(receipt), fieldNames(firstItem));
        }
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    /** 후보 경로를 순서대로 시도해 처음으로 값이 있는 노드를 돌려준다(전부 없으면 missing 노드). */
    private JsonNode firstPresent(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.isEmpty()) {
                return value;
            }
        }
        return node.path(fields[fields.length - 1]);
    }

    /**
     * 후보 경로를 순서대로 시도한 문자열. 필드 부재·null·빈 문자열은 모두 null 로 정규화한다
     * (빈 문자열을 저장하면 "없음"과 구분이 사라진다 — 반품 파서와 같은 규칙).
     */
    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String raw = value.asText();
            if (!raw.isBlank()) {
                return raw;
            }
        }
        return null;
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
