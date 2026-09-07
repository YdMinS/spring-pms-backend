package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * 쿠팡 응답의 금액 파싱 <b>단일 지점</b> (FEATURE_2609_26 / PLAN D9).
 *
 * <p>쿠팡 금액은 평범한 숫자가 아니라 protobuf Money 객체다(dev 실응답 확인, 2026-09-07):
 * <pre>"salesPrice": {"nanos": 0, "units": 13670, "currencyCode": "KRW"}</pre>
 *
 * <p><b>필수 규칙</b>: 금액을 읽는 코드는 전부 이 클래스를 통과한다. 호출부마다
 * {@code asLong()}/{@code path("units")} 를 직접 쓰면 형태가 하나만 바뀌어도 조용히 0 원이 저장된다.
 *
 * <p>사용 예:
 * <pre>
 * BigDecimal unitPrice = CoupangMoney.parse(item.get("salesPrice"));   // {units,nanos} → 13670
 * BigDecimal scalar    = CoupangMoney.parse(item.get("orderPrice"));   // 26990 → 26990 (방어)
 * BigDecimal missing   = CoupangMoney.parse(item.get("nope"));         // null
 * </pre>
 *
 * <p>⚠️ 알 수 없는 형태·null 은 <b>예외 대신 null</b>이다 — 한 건의 형태 이상이 적재 회차 전체를
 * 깨뜨리면 안 된다.
 * <p>⚠️ {@code currencyCode} 는 저장하지 않는다(전 계정 KRW). 다통화가 생기면 그때 컬럼을 만든다.
 */
public final class CoupangMoney {

    private CoupangMoney() {
    }

    /**
     * 금액 노드 → {@link BigDecimal}. 파싱 불가·부재는 {@code null}.
     *
     * <p>{@code nanos} 는 10⁻⁹ 단위다. KRW 는 항상 0 이지만 버리지 않는다 —
     * 계산에 넣어두면 통화가 늘어도 이 클래스가 깨지지 않는다.
     */
    public static BigDecimal parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isObject() && node.hasNonNull("units")) {
            long units = node.path("units").asLong(0L);
            long nanos = node.path("nanos").asLong(0L);
            return BigDecimal.valueOf(units).add(BigDecimal.valueOf(nanos, 9));
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
