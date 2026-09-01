package com.pms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 쿠팡 OpenAPI 연동 설정 (application.yml 의 oklyx.coupang.* 바인딩).
 *
 * {vendorId} 토큰은 호출 시 계정의 vendorId 로 치환된다.
 */
@Component
@ConfigurationProperties(prefix = "oklyx.coupang")
@Getter
@Setter
public class CoupangProperties {

    /** ordersheets(발주서 목록) 조회 경로. {vendorId} 치환. */
    private String ordersheetsPath = "/v2/providers/openapi/apis/api/v5/vendors/{vendorId}/ordersheets";

    /**
     * ordersheets 조회 기간(일). createdAtFrom = 오늘 − syncDays. 쿠팡은 범위 상한 < 31일.
     *
     * status 는 이 윈도우(주문 생성일 기준) 안의 주문만 갱신된다 — 윈도우를 벗어난 주문은 다시 조회되지
     * 않아 마지막 상태(예: 결제완료)로 얼어붙는다. 넓히면 쿠팡이 504(Gateway Timeout)를 자주 내므로,
     * 대신 조회/구매목록 화면도 같은 윈도우(syncDays)로 제한해 stale 행이 노출되지 않게 한다.
     * (표시 필터는 order_item.paidAt 기준 — 주문 createdAt 은 저장하지 않음.)
     */
    private int syncDays = 14;

    /**
     * INSTRUCT(상품준비중) 조회 윈도우(일). 송장 접수시트 생성 전용.
     * createdAtFrom = 오늘 − instructDays. INSTRUCT 는 결제완료(ACCEPT) 후 사용자가 나중에 수동 전환하므로
     * 주문 생성일(createdAt)이 오래됐을 수 있다 — 좁은 윈도우면 현재 INSTRUCT 주문이 조회에서 누락된다.
     * 그래서 syncDays 와 분리하고 쿠팡 상한(range < 31일)에 맞춰 최대치(30)로 넓게 잡는다.
     */
    private int instructDays = 30;

    /**
     * 주문번호 단건 발주서 조회 경로. {vendorId}/{orderId} 치환.
     *
     * 목록 조회(ordersheetsPath)와 달리 status 조건이 없어 결제완료·배송지시 등 어떤 상태의 주문도
     * 조회된다 — 주문 단건 송장시트가 상태와 무관할 수 있는 근거.
     * ⚠️ 실계정 검증 전이라 설정으로 뺀다(PLAN.md 미검증 항목).
     */
    private String ordersheetByOrderPath =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/{orderId}/ordersheets";

    /** 송장업로드(발송처리) 경로. {vendorId} 치환. 상품준비중(INSTRUCT)→배송지시(DEPARTURE) 전환. */
    private String invoicesPath = "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/invoices";

    /** returnRequests(반품/취소 요청 목록) 조회 경로. {vendorId} 치환. */
    private String returnrequestsPath = "/v2/providers/openapi/apis/api/v6/vendors/{vendorId}/returnRequests";

    /** 취소 보정 조회 기간(일). 취소는 늦게 처리되므로 ordersheets 보다 넉넉히. 쿠팡 최대 31일. */
    private int cancelSyncDays = 7;
}
