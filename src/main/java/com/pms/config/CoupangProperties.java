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
     * 대신 구매목록 화면을 같은 윈도우(syncDays)로 제한해 stale 행이 노출되지 않게 한다.
     * 주문내역 조회에는 이 값이 <b>기본 창</b>일 뿐이다 — from/to 를 명시하면 창 밖 과거도 조회하며,
     * stale 고지는 클라이언트 책임이다(FEATURE_2609_08).
     * (표시 필터는 order_item.paidAt 기준 — 주문 createdAt 은 저장하지 않음.)
     *
     * ⚠️ 31 이상으로 올리면 {@link com.pms.service.coupang.SyncWindow} 가 범위를 거절해
     * 부팅이 아니라 <b>첫 동기화</b>에서 IllegalArgumentException 이 난다(쿠팡 상한: range &lt; 31일).
     */
    private int syncDays = 14;

    /**
     * 종결 상태(배송지시~추적불가) 조회 창의 <b>하한</b>(일). 상한은 {@link #syncDays} 다.
     * 실제 창 = clamp(마지막 성공 이후 경과 + 1, 이 값, syncDays) — {@link com.pms.service.coupang.SyncWindow#recentSince}.
     * ⚠️ 1 로 낮추지 말 것: 창이 KST 달력 날짜 단위라 자정 경계에서 하루가 통째로 빈다.
     */
    private int terminalSyncMinDays = 3;

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

    /**
     * 발주처리(상품준비중 처리) 경로. {vendorId} 치환. 결제완료(ACCEPT)→상품준비중(INSTRUCT) 전환.
     * 바디는 {"vendorId":..., "shipmentBoxIds":[...]} 이고 전환은 되돌릴 수 없다.
     * ⚠️ 실계정 검증 전이라 설정으로 뺀다(ordersheet-by-order-path 와 같은 판단, PLAN 2609_17 D13).
     */
    private String acknowledgementPath =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets/acknowledgement";

    /**
     * 아이템(vendorItem)별 판매가 변경 경로. {vendorItemId}/{price} 치환. 재심사 없이 즉시 반영된다.
     * ⚠️ 실계정 미검증이라 설정으로 뺀다(ordersheet-by-order-path 와 같은 판단).
     * ⚠️ forceSalePriceUpdate 쿼리는 붙이지 말 것 — put() 에 쿼리 인자가 없고, path 에 섞으면 HMAC 서명이 깨진다.
     */
    private String vendorItemPricePath =
            "/v2/providers/openapi/apis/api/v1/marketplace/vendor-items/{vendorItemId}/prices/{price}";

    /** 송장업로드(발송처리) 경로. {vendorId} 치환. 상품준비중(INSTRUCT)→배송지시(DEPARTURE) 전환. */
    private String invoicesPath = "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/invoices";

    /**
     * 송장수정 경로(UPDATE_ORDER_INVOICE). {vendorId} 치환. dto 구조는 송장업로드와 동일.
     * 이미 배송지시 이상인 박스의 운송장을 정정할 때만 쓴다(PLAN 2609_11 D3).
     * ⚠️ 실계정 검증 전이라 설정으로 뺀다(ordersheet-by-order-path 와 같은 판단).
     */
    private String updateInvoicesPath =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/updateInvoices";

    /** returnRequests(반품/취소 요청 목록) 조회 경로. {vendorId} 치환. */
    private String returnrequestsPath = "/v2/providers/openapi/apis/api/v6/vendors/{vendorId}/returnRequests";

    /** 취소 보정 조회 기간(일). 취소는 늦게 처리되므로 ordersheets 보다 넉넉히. 쿠팡 최대 31일. */
    private int cancelSyncDays = 7;

    /** 쿠팡 API connect 타임아웃(ms). 미설정 시 무제한 → 게이트웨이 지연이 요청 스레드를 무한 점유한다. */
    private int connectTimeoutMs = 10_000;

    /** 쿠팡 API read 타임아웃(ms). 송장시트처럼 무거운 조회도 있어 넉넉히 잡되 무제한은 금지. */
    private int readTimeoutMs = 60_000;
}
