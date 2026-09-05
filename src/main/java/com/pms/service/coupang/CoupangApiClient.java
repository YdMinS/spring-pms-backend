package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;

/**
 * 쿠팡 OpenAPI 게이트웨이에 서명된 요청을 보내는 클라이언트의 계약(seam).
 *
 * 계정({@link MarketplaceAccount})의 accessKey/secretKey 로 HMAC 서명을 만들어 호출한다.
 * 이 인터페이스에만 의존하도록 모든 소비자(OrderSync/ReturnSync/ShippingLabel/ShipmentConfirm)를
 * 구성해, 프로파일에 따라 구현을 갈아끼울 수 있게 한다(테스트/로컬 seam).
 *
 * <ul>
 *   <li>{@link CoupangApiClientImpl} — {@code @Profile("!local")}: 실제 라이브 호출(RestClient).</li>
 *   <li>{@link MockCoupangApiClient} — {@code @Profile("local")}: 라이브 호출 없이 fixture JSON 반환.</li>
 * </ul>
 */
public interface CoupangApiClient {

    /**
     * 서명된 GET 요청.
     *
     * @param path    쿼리 제외 경로
     * @param query   인코딩된 쿼리스트링 (없으면 "")
     * @param account 호출 주체 계정 (자격증명 제공)
     * @return 응답 바디 (raw JSON 문자열)
     */
    String get(String path, String query, MarketplaceAccount account);

    /**
     * 서명된 POST 요청 (JSON 바디).
     *
     * @param path    쿼리 제외 경로
     * @param body    JSON 바디 문자열
     * @param account 호출 주체 계정 (자격증명 제공)
     * @return 응답 바디 (raw JSON 문자열)
     */
    String post(String path, String body, MarketplaceAccount account);

    /**
     * 서명된 PUT 요청 (JSON 바디). 상품 수정(전체 재전송)·판매중지 등에 사용 (FEATURE_2608_06 / 3c).
     *
     * @param path    쿼리 제외 경로
     * @param body    JSON 바디 문자열
     * @param account 호출 주체 계정 (자격증명 제공)
     * @return 응답 바디 (raw JSON 문자열)
     */
    String put(String path, String body, MarketplaceAccount account);

    /**
     * 서명된 PATCH 요청 (JSON 바디). 클레임 처리 액션 전용 (FEATURE_2609_21 / PLAN D11).
     *
     * ⚠️ {@link #put} 으로 우회하지 말 것 — HMAC 은 method 를 서명에 넣으므로 서명 자체는 통과하지만
     * 쿠팡 게이트웨이가 405 를 준다. 반품 입고확인·승인, 교환 입고확인·거부가 전부 PATCH 다.
     *
     * @param path    쿼리 제외 경로
     * @param body    JSON 바디 문자열
     * @param account 호출 주체 계정 (자격증명 제공)
     * @return 응답 바디 (raw JSON 문자열)
     */
    String patch(String path, String body, MarketplaceAccount account);
}
