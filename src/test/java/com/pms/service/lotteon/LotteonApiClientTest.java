package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.LotteonProperties;
import com.pms.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** FEATURE_2610_24: bearer key (D4) · dev stop (D8) · LotteON text on failure (D23) · per-line results. */
class LotteonApiClientTest {

    private LotteonProperties properties;
    private MockRestServiceServer server;
    private LotteonApiClient client;

    @BeforeEach
    void setUp() {
        properties = new LotteonProperties();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LotteonApiClient(builder, properties);
    }

    @Test
    void post_sendsTheKeyAsBearer_andTheJsonBody() {
        server.expect(requestTo("https://openapi.lotteon.com" + LotteonPaths.PRODUCT_STATUS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer key-1"))
                .andExpect(content().json("{\"spdNo\":\"LO1\"}"))
                .andRespond(withSuccess("{\"returnCode\":\"0000\",\"data\":[]}", MediaType.APPLICATION_JSON));

        JsonNode body = client.post(LotteonPaths.PRODUCT_STATUS, Map.of("spdNo", "LO1"), "key-1");

        assertThat(body.path("returnCode").asText()).isEqualTo("0000");
        server.verify();
    }

    @Test
    void getCategory_goesToTheCategoryHost() {
        server.expect(requestTo("https://onpick-api.lotteon.com" + LotteonPaths.STANDARD_CATEGORY + "&skip=0"))
                .andExpect(header("Authorization", "Bearer key-1"))
                .andRespond(withSuccess("{\"itemList\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.getCategory(LotteonPaths.STANDARD_CATEGORY + "&skip=0", "key-1").has("itemList")).isTrue();
        server.verify();
    }

    @Test
    void outboundDisabled_is503_withoutCalling() {
        properties.setOutboundEnabled(false);

        assertThatThrownBy(() -> client.get(LotteonPaths.IDENTITY, "key-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("개발 서버에서는 롯데ON 을 부르지 않습니다")
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void returnCodeNotSuccess_is502_withLotteonText() {
        server.expect(requestTo("https://openapi.lotteon.com" + LotteonPaths.IDENTITY))
                .andRespond(withSuccess("{\"returnCode\":\"3000\",\"message\":\"처리중 오류가 발생하였습니다.\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.get(LotteonPaths.IDENTITY, "key-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("처리중 오류가 발생하였습니다.")
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void httpError_is502_withLotteonTextOrTheFallback() {
        server.expect(requestTo("https://openapi.lotteon.com" + LotteonPaths.IDENTITY))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"등록되지 않은 OpenAPI Key 입니다.\"}"));

        assertThatThrownBy(() -> client.get(LotteonPaths.IDENTITY, "bad"))
                .hasMessage("등록되지 않은 OpenAPI Key 입니다.");

        server.reset();
        server.expect(requestTo("https://openapi.lotteon.com" + LotteonPaths.IDENTITY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.get(LotteonPaths.IDENTITY, "bad"))
                .hasMessage("롯데ON 응답 오류(HTTP 429)");
    }

    @Test
    void lineFailure_isTheFirstFailedLinesText() throws Exception {
        JsonNode ok = new ObjectMapper().readTree("{\"data\":[{\"resultCode\":\"0000\"}]}");
        JsonNode failed = new ObjectMapper().readTree("{\"message\":\"정상\",\"data\":[{\"resultCode\":\"0000\"},"
                + "{\"resultCode\":\"1001\",\"resultMessage\":\"[원산지코드]은 필수입력입니다.\"}]}");

        assertThat(LotteonApiClient.lineFailure(ok)).isNull();
        assertThat(LotteonApiClient.lineFailure(failed)).isEqualTo("[원산지코드]은 필수입력입니다.");
    }
}
