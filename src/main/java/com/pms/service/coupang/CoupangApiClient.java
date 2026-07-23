package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.service.external.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.function.Supplier;

/**
 * 쿠팡 OpenAPI 게이트웨이에 서명된 요청을 보내는 클라이언트 (Spring RestClient).
 *
 * 계정({@link MarketplaceAccount})의 accessKey/secretKey 로 {@link CoupangHmacSigner} 서명을 만들어
 * Authorization 헤더에 실어 호출한다. Phase 1 은 GET 골격까지만 — Phase 2~3 에서
 * ordersheets/returnRequests path·query 를 이 client 로 호출한다.
 *
 * <p><b>로깅 초크포인트</b>: 모든 쿠팡 raw 응답이 여기 {@link #execute}를 통과한다.
 * <ul>
 *   <li>INFO(상시): method·path·query·elapsedMs·응답 byte 크기 — raw 바디 없음</li>
 *   <li>DEBUG(토글 시): raw 바디 — 항상 {@link PiiMasker#mask} 통과</li>
 *   <li>WARN(실패 시): status + raw 바디 — 항상 {@link PiiMasker#mask} 통과</li>
 * </ul>
 * raw 는 어느 경로로 나가도 PII 마스킹된다.
 */
@Component
public class CoupangApiClient {

    private static final Logger log = LoggerFactory.getLogger(CoupangApiClient.class);

    private static final String HOST = "https://api-gateway.coupang.com";

    private final RestClient restClient;
    private final CoupangHmacSigner signer;
    private final PiiMasker piiMasker;

    public CoupangApiClient(RestClient.Builder builder, CoupangHmacSigner signer, PiiMasker piiMasker) {
        // Inject the auto-configured builder so tests can bind MockRestServiceServer to it.
        this.restClient = builder.baseUrl(HOST).build();
        this.signer = signer;
        this.piiMasker = piiMasker;
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
        return execute("GET", path, query, () -> restClient.get().uri(URI.create(HOST + uri))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .retrieve().body(String.class));
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
        return execute("POST", path, "", () -> restClient.post().uri(URI.create(HOST + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .body(body).retrieve().body(String.class));
    }

    /**
     * 실제 호출을 감싸 로깅을 일원화한다. raw 는 DEBUG/실패 경로에서만, 항상 mask() 를 통과한다.
     */
    private String execute(String method, String path, String query, Supplier<String> call) {
        long t0 = System.nanoTime();
        try {
            String body = call.get();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // Always-on summary: no raw body, byte size only.
            log.info("[COUPANG] {} {} q={} {}ms bytes={}", method, path, query, ms,
                    body == null ? 0 : body.length());
            if (log.isDebugEnabled()) {
                log.debug("[COUPANG] resp={}", piiMasker.mask(body));
            }
            return body;
        } catch (RestClientResponseException e) { // non-2xx: has a response body
            log.warn("[COUPANG] {} {} FAIL status={} resp={}", method, path,
                    e.getStatusCode().value(), piiMasker.mask(e.getResponseBodyAsString()));
            throw e;
        } catch (RestClientException e) { // transport error: no body
            log.warn("[COUPANG] {} {} FAIL {}", method, path, e.getMessage());
            throw e;
        }
    }
}
