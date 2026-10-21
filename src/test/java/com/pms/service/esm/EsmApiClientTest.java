package com.pms.service.esm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.EsmProperties;
import com.pms.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** FEATURE_2610_26: token shape (D6 · D69) · dev stop (D10) · ESM text on failure (D25 · D38). */
class EsmApiClientTest {

    // D69: the ESM guide's sample secret key — 24 bytes (192 bits), signed as is with no key length check.
    private static final String SECRET = "ZDE2YjlmZjUtYTc0Mi00ZWU3";
    private static final EsmKey KEY = new EsmKey("master-1", SECRET, "g-seller", "a-seller");
    private static final ObjectMapper JSON = new ObjectMapper();

    private EsmProperties properties;
    private MockRestServiceServer server;
    private EsmApiClient client;

    @BeforeEach
    void setUp() {
        properties = new EsmProperties();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EsmApiClient(builder, properties);
    }

    @Test
    void get_sendsBearerTokenSignedWithTheSecret_bothSitesAuctionFirst() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server.expect(requestTo("https://sa2.esmplus.com/item/v1/shipping/places?pageSize=1&pageIndex=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> authorization.set(request.getHeaders().getFirst("Authorization")))
                .andRespond(withSuccess("{\"totalCount\":1}", MediaType.APPLICATION_JSON));

        JsonNode body = client.get(EsmPaths.SHIPPING_PLACES + "?pageSize=1&pageIndex=1", KEY);

        assertThat(body.get("totalCount").asInt()).isEqualTo(1);
        String[] parts = authorization.get().substring("Bearer ".length()).split("\\.");
        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.get("alg").asText()).isEqualTo("HS256");
        assertThat(header.get("typ").asText()).isEqualTo("JWT");
        assertThat(header.get("kid").asText()).isEqualTo("master-1");
        assertThat(payload.get("iss").asText()).isEqualTo("www.esmplus.com");
        assertThat(payload.get("sub").asText()).isEqualTo("sell");
        assertThat(payload.get("aud").asText()).isEqualTo("sa.esmplus.com");
        assertThat(payload.get("iat").isNumber()).isTrue();
        assertThat(payload.get("ssi").asText()).isEqualTo("A:a-seller,G:g-seller");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        assertThat(parts[2]).isEqualTo(expected);
        server.verify();
    }

    @Test
    void siteLookup_putsOnlyThatSiteInTheToken() {
        assertThat(EsmTokens.ssi(KEY, EsmSite.GMARKET)).isEqualTo("G:g-seller");
        assertThat(EsmTokens.ssi(KEY, EsmSite.AUCTION)).isEqualTo("A:a-seller");
    }

    @Test
    void outboundDisabled_failsWithoutNetworkCall() {
        properties.setOutboundEnabled(false);

        assertThatThrownBy(() -> client.get(EsmPaths.DISPATCH_POLICIES, KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessage("개발 서버에서는 G마켓 · 옥션을 부르지 않습니다")
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    void httpError_becomes502WithEsmText() {
        server.expect(requestTo("https://sa2.esmplus.com/item/v1/shipping/dispatch-policies"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":{\"message\":\"The user does not have right access to the api\","
                                + "\"status_code\":401}}"));

        assertThatThrownBy(() -> client.get(EsmPaths.DISPATCH_POLICIES, KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessage("The user does not have right access to the api")
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void resultCodeNotZero_onSuccessStatus_isAFailure() {
        server.expect(requestTo("https://sa2.esmplus.com/item/v1/goods"))
                .andRespond(withSuccess("{\"resultCode\":1000,\"message\":\"[InvalidApiRequestEx][GMKT]카테고리 코드가 없습니다.\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.post(EsmPaths.GOODS, java.util.Map.of("a", 1), KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessage("[InvalidApiRequestEx][GMKT]카테고리 코드가 없습니다.");
    }

    @Test
    void noText_fallsBackToStatusWording() {
        assertThat(EsmApiClient.messageOf(JSON.createObjectNode(), 500)).isEqualTo("G마켓 · 옥션 응답 오류(HTTP 500)");
    }
}
