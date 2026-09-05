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
     *
     * ⚠️ 네임스페이스는 <b>seller_api</b> 다 — 상품 API 라서 {@code CoupangListingAdapter.SELLER_PRODUCTS}
     * ({@code /v2/providers/seller_api/apis/api/v1/marketplace/seller-products}) 와 같은 그룹이다.
     * 이 파일의 다른 경로들이 전부 openapi 인 것은 그쪽이 주문·반품 API 이기 때문이고, 여기에 openapi 를
     * 쓰면 쿠팡이 404 PRECONDITION_FAILED("No exactly matching API specification") 를 준다(2026-09-05 실계정).
     * ⚠️ forceSalePriceUpdate 쿼리는 붙이지 말 것 — put() 에 쿼리 인자가 없고, path 에 섞으면 HMAC 서명이 깨진다.
     */
    private String vendorItemPricePath =
            "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/{vendorItemId}/prices/{price}";

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

    /**
     * 클레임 주문 백필의 회차당 단건 조회 상한(orderId 기준, D13). 0 = 백필 비활성.
     * 건수가 아니라 호출 수의 상한이다 — 같은 orderId 의 클레임 여러 건은 1회로 합쳐진다.
     */
    private int claimBackfillMaxOrders = 20;

    /**
     * 한 클레임의 재매칭 시도 상한(D13). 초과하면 조회 대상에서 영구히 빠진다 —
     * 쿠팡에도 없는 주문(삭제·타 계정)을 매 회차 다시 치지 않기 위한 포기 조건이다.
     */
    private int claimBackfillMaxAttempts = 3;

    /** 클레임 조회 창의 상한(일). 하한은 cancel-sync-days 다. 쿠팡 상한이 31일 미만이라 30을 넘기지 말 것. */
    private int claimWindowMaxDays = 30;

    /** 이 일수를 넘긴 미완결 클레임은 STALE 로 강제 종결한다(D11). 추적 슬라이스의 폭을 실질적으로 결정한다. */
    private int claimStaleDays = 30;

    /**
     * 회차당 추적 슬라이스 상한(D10) = 호출 폭주 안전망. 0 = 슬라이스 조회 비활성.
     * ⚠️ 0 이어도 STALE 스윕은 돈다 — 스윕은 쿠팡을 치지 않는 로컬 종결이고, 이걸 같이 끄면
     * 미완결이 무한히 쌓여 다시 켤 때 슬라이스가 폭발한다.
     */
    private int claimTrackingMaxSlices = 6;

    /**
     * exchangeRequests(교환 요청 목록) 조회 경로. {vendorId} 치환.
     * ⚠️ 실계정 검증 전이라 상수가 아니라 설정으로 뺀다(ordersheet-by-order-path 와 같은 판단).
     */
    private String exchangeRequestsPath =
            "/v2/providers/openapi/apis/api/v1/marketplace/vendors/{vendorId}/exchangeRequests";

    /**
     * 교환 신규 조회 창(일). ⚠️ 쿠팡 상한이 7일이라 이 값을 넘기지 말 것(D9·PLAN §4) —
     * 반품과 달리 {@code lastClaimSyncAt} 으로 넓힐 수 없다(넓히면 쿠팡이 거절한다).
     */
    private int exchangeWindowDays = 7;

    /** 교환 조회 페이지 크기. ⚠️ 쿠팡 기본값이 10 이라 명시하지 않으면 페이지 수가 5배가 된다. */
    private int exchangeMaxPerPage = 50;

    /** 쿠팡 API connect 타임아웃(ms). 미설정 시 무제한 → 게이트웨이 지연이 요청 스레드를 무한 점유한다. */
    private int connectTimeoutMs = 10_000;

    /** 쿠팡 API read 타임아웃(ms). 송장시트처럼 무거운 조회도 있어 넉넉히 잡되 무제한은 금지. */
    private int readTimeoutMs = 60_000;
}
