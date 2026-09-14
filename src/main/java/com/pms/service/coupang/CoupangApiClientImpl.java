package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.service.external.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link CoupangApiClient} 의 라이브 구현 — 쿠팡 OpenAPI 게이트웨이에 실제 서명 요청(Spring RestClient).
 *
 * 계정({@link MarketplaceAccount})의 accessKey/secretKey 로 {@link CoupangHmacSigner} 서명을 만들어
 * Authorization 헤더에 실어 호출한다.
 *
 * <p><b>프로파일</b>: {@code @Profile("!local")} — local 에서는 라이브 호출을 막고
 * {@link MockCoupangApiClient} 가 대신 주입된다.
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
@Profile("!local")
public class CoupangApiClientImpl implements CoupangApiClient {

    private static final Logger log = LoggerFactory.getLogger(CoupangApiClientImpl.class);

    private static final String HOST = "https://api-gateway.coupang.com";

    private final RestClient restClient;
    private final CoupangHmacSigner signer;
    private final PiiMasker piiMasker;
    private final CoupangRateLimitGuard rateLimitGuard;
    private final CoupangCallBudget callBudget;

    /** 로그로 남길 진단 헤더 화이트리스트(소문자 접두사 비교). 이 밖의 헤더는 절대 기록하지 않는다. */
    private static final List<String> DIAGNOSTIC_HEADER_PREFIXES = List.of("x-cag", "x-ratelimit", "retry-after");

    public CoupangApiClientImpl(RestClient.Builder builder, CoupangHmacSigner signer, PiiMasker piiMasker,
                                CoupangRateLimitGuard rateLimitGuard, CoupangCallBudget callBudget) {
        // Inject the auto-configured builder so tests can bind MockRestServiceServer to it.
        this.restClient = builder.baseUrl(HOST).build();
        this.signer = signer;
        this.piiMasker = piiMasker;
        this.rateLimitGuard = rateLimitGuard;
        this.callBudget = callBudget;
    }

    @Override
    public String get(String path, String query, MarketplaceAccount account) {
        String auth = signer.authorization("GET", path, query,
                CoupangCredentials.of(account).getAccessKey(), CoupangCredentials.of(account).getSecretKey());
        String uri = query.isEmpty() ? path : path + "?" + query;
        // URI.create 로 전송: 이미 인코딩된 쿼리(%2B 등)가 RestClient 템플릿 인코딩으로 재인코딩되지
        // 않게 해, 서명 대상 query 와 실제 전송 query 를 동일하게 유지한다.
        return execute("GET", path, query, account, () -> restClient.get().uri(URI.create(HOST + uri))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .retrieve().toEntity(String.class));
    }

    @Override
    public String post(String path, String body, MarketplaceAccount account) {
        // 쿠팡 HMAC 은 method+path+query 만 서명(바디 제외)하므로 query="" 로 서명한다.
        String auth = signer.authorization("POST", path, "",
                CoupangCredentials.of(account).getAccessKey(), CoupangCredentials.of(account).getSecretKey());
        return execute("POST", path, "", account, () -> restClient.post().uri(URI.create(HOST + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .body(body).retrieve().toEntity(String.class));
    }

    @Override
    public String put(String path, String body, MarketplaceAccount account) {
        // POST 와 동일: 쿠팡 HMAC 은 method+path+query 만 서명(바디 제외)하므로 query="" 로 서명한다.
        String auth = signer.authorization("PUT", path, "",
                CoupangCredentials.of(account).getAccessKey(), CoupangCredentials.of(account).getSecretKey());
        return execute("PUT", path, "", account, () -> restClient.put().uri(URI.create(HOST + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .body(body).retrieve().toEntity(String.class));
    }

    @Override
    public String patch(String path, String body, MarketplaceAccount account) {
        // POST/PUT 과 동일: 쿠팡 HMAC 은 method+path+query 만 서명(바디 제외)하므로 query="" 로 서명한다.
        String auth = signer.authorization("PATCH", path, "",
                CoupangCredentials.of(account).getAccessKey(), CoupangCredentials.of(account).getSecretKey());
        return execute("PATCH", path, "", account, () -> restClient.patch().uri(URI.create(HOST + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .body(body).retrieve().toEntity(String.class));
    }

    /**
     * 실제 호출을 감싸 로깅을 일원화한다. raw 는 DEBUG/실패 경로에서만, 항상 mask() 를 통과한다.
     *
     * 모든 쿠팡 호출이 여기를 지나므로 429 쿨다운 서킷({@link CoupangRateLimitGuard})과 속도 제한
     * ({@link CoupangCallBudget})도 여기서만 건다 — 호출 직전 차단창 확인 → 토큰 획득(대기 가능) →
     * 429 수신 시 그 업체코드의 차단창 개시(재시도 금지).
     *
     * <p>🔴 429 는 첫 수신분도 {@link CoupangRateLimitedException}(HTTP 429) 으로 바꿔 던진다
     * (PLAN 2609_31 D9-1) — 쿨다운 중 차단({@code check()})과 호출자가 보는 모양을 하나로 맞춘다.
     */
    private String execute(String method, String path, String query, MarketplaceAccount account,
                           Supplier<ResponseEntity<String>> call) {
        String vendorId = CoupangCredentials.of(account).getVendorId();
        // ⚠️ 순서를 바꾸지 말 것 — 차단창이 열린 계정을 버킷에서 기다리게 하면 토큰만 낭비한다.
        rateLimitGuard.check(vendorId);   // 차단창이면 즉시 실패 (쿠팡을 치지 않는다)
        callBudget.acquire(vendorId);     // 속도 제한 — 여기서 대기할 수 있다
        long t0 = System.nanoTime();
        try {
            ResponseEntity<String> response = call.get();
            String body = response.getBody();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // Always-on summary: no raw body, byte size only.
            log.info("[COUPANG] {} {} q={} {}ms bytes={}", method, path, query, ms,
                    body == null ? 0 : body.length());
            logDiagnosticHeaders(method, path, diagnosticHeaders(response.getHeaders()));
            if (log.isDebugEnabled()) {
                log.debug("[COUPANG] resp={}", piiMasker.mask(body));
            }
            return body;
        } catch (RestClientResponseException e) { // non-2xx: has a response body
            log.warn("[COUPANG] {} {} FAIL status={} resp={} headers={}", method, path,
                    e.getStatusCode().value(), piiMasker.mask(e.getResponseBodyAsString()),
                    diagnosticHeaders(e.getResponseHeaders()));
            if (e.getStatusCode().value() == 429) {
                // 🔴 PLAN 2609_31 D9-1 — 첫 429 만 RestClientResponseException(→ 500)으로 나가면 호출자가
                //    "쿠팡 오류"와 구분하지 못한다. 두 번째 호출부터(check())와 같은 모양
                //    (HTTP 429 + 재시도 가능 시각)으로 맞춘다.
                //    Coupang: back off ~10 min instead of retrying.
                throw new CoupangRateLimitedException(rateLimitGuard.trip(vendorId));
            }
            if (e.getStatusCode().value() == 403) {
                // IP-level block: Coupang cuts off the caller IP after ~20 error responses in 5s and it is
                // NOT the per-vendor 429. One occurrence means we are close to a block — surface it loudly.
                // 🔴 403 에 쿨다운을 걸지 말 것 — 가드는 429 전용이다.
                log.warn("[COUPANG][ALERT] 403 access denied — IP 차단 임박. path={} vendor={}", path, vendorId);
            }
            throw e;
        } catch (RestClientException e) { // transport error: no body
            log.warn("[COUPANG] {} {} FAIL {}", method, path, e.getMessage());
            throw e;
        }
    }

    /**
     * 성공 응답의 진단 헤더를 남긴다.
     *
     * <p>{@code x-cag} 로 시작하는 헤더가 하나라도 있으면 WARN — 쿠팡이 쓰로틀링 임계치에 근접했다는
     * 사전 경고이므로 DEBUG 로 묻으면 보지 못한다.
     */
    private void logDiagnosticHeaders(String method, String path, List<String> headers) {
        if (headers.isEmpty()) {
            return;
        }
        boolean throttleWarning = headers.stream()
                .anyMatch(header -> header.toLowerCase(Locale.ROOT).startsWith("x-cag"));
        if (throttleWarning) {
            log.warn("[COUPANG][THROTTLE-WARN] {} {} {}", method, path, headers);
        } else if (log.isDebugEnabled()) {
            log.debug("[COUPANG] {} {} headers={}", method, path, headers);
        }
    }

    /**
     * 진단 가치가 있는 응답 헤더만 골라낸다 — 응답 헤더 전체를 로그에 붓지 않기 위한 화이트리스트.
     *
     * <p>{@code X-CAG-Warnings} 는 쿠팡이 쓰로틀링 임계치 약 90% 에서 보내는 사전 경고다(문서 확인
     * 2026-09-14). 값 형식은 비공개라 우선 관측만 한다 — 이 값으로 속도를 자동 조절하지 않는다(PLAN D4).
     *
     * <p>⚠️ 화이트리스트 밖 헤더({@code Authorization}·{@code Set-Cookie} 등)는 절대 남기지 않는다.
     */
    static List<String> diagnosticHeaders(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }
        List<String> picked = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (DIAGNOSTIC_HEADER_PREFIXES.stream().noneMatch(lower::startsWith)) {
                continue;
            }
            for (String value : entry.getValue()) {
                picked.add(name + ": " + value);
            }
        }
        return picked;
    }
}
