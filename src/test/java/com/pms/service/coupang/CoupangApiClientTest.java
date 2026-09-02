package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.CoupangRateLimitedException;
import com.pms.service.external.PiiMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

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

    @Mock
    private MarketplaceAccount account;

    private MockRestServiceServer server;
    private CoupangApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // 가드는 실객체 — @Mock 이면 check() 가 no-op 이라 429 단축회로를 검증할 수 없다.
        client = new CoupangApiClientImpl(builder, signer, piiMasker,
                new CoupangRateLimitGuard(Clock.systemDefaultZone()));
    }

    @Test
    void execute_실패raw마스킹() {
        String rawBody = "{\"name\":\"김철수\"}";
        given(account.getAccessKey()).willReturn("ak");
        given(account.getSecretKey()).willReturn("sk");
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
        given(account.getAccessKey()).willReturn("ak");
        given(account.getSecretKey()).willReturn("sk");
        given(signer.authorization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .willReturn("auth");
        server.expect(requestTo("https://api-gateway.coupang.com/v5/orders"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}"));

        assertThatThrownBy(() -> client.get("/v5/orders", "", account))
                .isInstanceOf(RestClientResponseException.class);

        // 쿨다운이 열렸으므로 두 번째 호출은 서버에 도달하지 않는다(쿠팡 지침: 재시도 금지).
        assertThatThrownBy(() -> client.get("/v5/orders", "", account))
                .isInstanceOf(CoupangRateLimitedException.class);
        server.verify();    // 요청은 1건뿐
    }
}
