package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * 쿠팡 OpenAPI 게이트웨이에 서명된 요청을 보내는 클라이언트 (Spring RestClient).
 *
 * 계정({@link MarketplaceAccount})의 accessKey/secretKey 로 {@link CoupangHmacSigner} 서명을 만들어
 * Authorization 헤더에 실어 호출한다. Phase 1 은 GET 골격까지만 — Phase 2~3 에서
 * ordersheets/returnRequests path·query 를 이 client 로 호출한다.
 */
@Component
public class CoupangApiClient {

    private static final String HOST = "https://api-gateway.coupang.com";

    private final RestClient restClient = RestClient.builder().baseUrl(HOST).build();
    private final CoupangHmacSigner signer;

    public CoupangApiClient(CoupangHmacSigner signer) {
        this.signer = signer;
    }

    /**
     * 서명된 GET 요청.
     *
     * @param path    쿼리 제외 경로
     * @param query   인코딩된 쿼리스트링 (없으면 "")
     * @param account 호출 주체 계정 (자격증명 제공)
     * @return 응답 바디 (raw JSON 문자열)
     */
    public String get(String path, String query, MarketplaceAccount account) {
        String auth = signer.authorization("GET", path, query,
                account.getAccessKey(), account.getSecretKey());
        String uri = query.isEmpty() ? path : path + "?" + query;
        // URI.create 로 전송: 이미 인코딩된 쿼리(%2B 등)가 RestClient 템플릿 인코딩으로 재인코딩되지
        // 않게 해, 서명 대상 query 와 실제 전송 query 를 동일하게 유지한다.
        return restClient.get().uri(URI.create(HOST + uri))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .retrieve().body(String.class);
    }

    /**
     * 서명된 POST 요청 (JSON 바디).
     *
     * 쿠팡 HMAC 은 method+path+query 만 서명(바디 제외)하므로 query="" 로 서명한다.
     *
     * @param path    쿼리 제외 경로
     * @param body    JSON 바디 문자열
     * @param account 호출 주체 계정 (자격증명 제공)
     * @return 응답 바디 (raw JSON 문자열)
     */
    public String post(String path, String body, MarketplaceAccount account) {
        String auth = signer.authorization("POST", path, "",
                account.getAccessKey(), account.getSecretKey());
        return restClient.post().uri(URI.create(HOST + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .body(body).retrieve().body(String.class);
    }
}
