package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.service.external.PiiMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * CoupangApiClient 로깅 초크포인트의 핵심 안전요건 검증.
 *
 * MockRestServiceServer 를 주입 builder 에 바인딩해 응답/에러를 구성한다.
 * 실패 raw 는 isDebugEnabled 와 무관하게 항상 mask() 를 통과함을 1건으로 증명.
 */
@ExtendWith(MockitoExtension.class)
class CoupangApiClientTest {

    @Mock
    private CoupangHmacSigner signer;

    @Mock
    private PiiMasker piiMasker;

    // 실객체 — 자격증명은 별도 엔티티라 mock 이면 CoupangCredentials.of() 가 400 을 던진다.
    private final MarketplaceAccount account =
            MarketplaceAccountFixture.coupangStubBuilder("V1", null, "ak", "sk").build();

    // 다른 업체코드의 계정 — 쿨다운이 계정별임을 검증한다(PLAN 2609_46 D1).
    private final MarketplaceAccount otherAccount =
            MarketplaceAccountFixture.coupangStubBuilder("V2", null, "ak", "sk").build();

    private MockRestServiceServer server;
    private CoupangApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // 가드·버킷 모두 실객체 — @Mock 이면 check() 가 no-op 이라 429 단축회로를 검증할 수 없다.
        // 버킷의 Waiter 는 즉시 반환 스텁이라 테스트가 느려지지 않는다.
        client = new CoupangApiClientImpl(builder, signer, piiMasker,
                new CoupangRateLimitGuard(Clock.systemDefaultZone()),
                new CoupangCallBudget(new CoupangProperties(), Clock.systemDefaultZone(), duration -> { }));
    }

    @Test
    void execute_실패raw마스킹() {
        String rawBody = "{\"name\":\"김철수\"}";
        given(signer.authorization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .willReturn("auth");
        server.expect(requestTo("https://api-gateway.coupang.com/v5/orders"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(rawBody));

        assertThatThrownBy(() -> client.get("/v5/orders", "", account))
                .isInstanceOf(RestClientResponseException.class);

        // Failure raw always passes through mask() regardless of log level.
        verify(piiMasker).mask(rawBody);
    }

    @Test
    void execute_429후_다음호출은_쿠팡을_치지않고_차단() {
        given(signer.authorization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .willReturn("auth");
        server.expect(requestTo("https://api-gateway.coupang.com/v5/orders"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}"));

        // 🔴 PLAN 2609_31 D9-1 — 첫 429 도 CoupangRateLimitedException(HTTP 429)으로 바꿔 던진다.
        assertThatThrownBy(() -> client.get("/v5/orders", "", account))
                .isInstanceOf(CoupangRateLimitedException.class);

        // 쿨다운이 열렸으므로 두 번째 호출은 서버에 도달하지 않는다(쿠팡 지침: 재시도 금지).
        assertThatThrownBy(() -> client.get("/v5/orders", "", account))
                .isInstanceOf(CoupangRateLimitedException.class);
        server.verify();    // 요청은 1건뿐
    }

    @Test
    void execute_429후_다른계정은_정상호출() {
        given(signer.authorization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .willReturn("auth");
        server.expect(requestTo("https://api-gateway.coupang.com/v5/orders"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}"));
        server.expect(requestTo("https://api-gateway.coupang.com/v5/orders"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.get("/v5/orders", "", account))
                .isInstanceOf(CoupangRateLimitedException.class);

        // 🔴 쿨다운은 업체코드별이다 — 다른 계정은 그대로 쿠팡을 친다.
        assertThat(client.get("/v5/orders", "", otherAccount)).isEqualTo("{\"ok\":true}");
        server.verify();    // 요청 2건(A 1건 + B 1건)
    }

    @Test
    void execute_응답헤더가있어도_바디를_그대로반환() {
        given(signer.authorization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .willReturn("auth");
        server.expect(requestTo("https://api-gateway.coupang.com/v5/orders"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON)
                        .headers(headersWith("X-CAG-Warnings", "threshold-90")));

        // toEntity 전환 회귀 방지 — 호출부가 받는 값은 여전히 바디 문자열이다.
        assertThat(client.get("/v5/orders", "", account)).isEqualTo("{\"data\":[]}");
    }

    private static HttpHeaders headersWith(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(name, value);
        return headers;
    }

    /** 진단 헤더 화이트리스트 — 순수 단위(HTTP 없음). */
    @Nested
    class DiagnosticHeaders {

        @Test
        void diagnosticHeaders_picksThrottleWarning() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-CAG-Warnings", "threshold-90");
            headers.add("Content-Type", "application/json");

            assertThat(CoupangApiClientImpl.diagnosticHeaders(headers))
                    .containsExactly("X-CAG-Warnings: threshold-90");
        }

        @Test
        void diagnosticHeaders_dropsEverythingElse() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "secret");
            headers.add("Set-Cookie", "session=abc");
            headers.add("Content-Type", "application/json");

            assertThat(CoupangApiClientImpl.diagnosticHeaders(headers)).isEmpty();
        }

        @Test
        void diagnosticHeaders_isCaseInsensitive() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("x-cag-warnings", "threshold-90");

            List<String> picked = CoupangApiClientImpl.diagnosticHeaders(headers);

            assertThat(picked).containsExactly("x-cag-warnings: threshold-90");
        }
    }
}
