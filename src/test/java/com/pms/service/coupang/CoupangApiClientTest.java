package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;
import com.pms.service.external.PiiMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
        client = new CoupangApiClient(builder, signer, piiMasker);
    }

    @Test
    void execute_실패raw마스킹() {
        String rawBody = "{\"name\":\"김철수\"}";
        given(account.getAccessKey()).willReturn("ak");
        given(account.getSecretKey()).willReturn("sk");
        given(signer.authorization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .willReturn("auth");
        server.expect(requestTo("https://api-gateway.coupang.com/v5/orders"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(rawBody));

        assertThatThrownBy(() -> client.get("/v5/orders", "", account))
                .isInstanceOf(RestClientResponseException.class);

        // Failure raw always passes through mask() regardless of log level.
        verify(piiMasker).mask(rawBody);
    }
}
