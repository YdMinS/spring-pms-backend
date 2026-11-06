package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.LotteonProperties;
import com.pms.exception.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;

/**
 * The single client for the LotteON Open API (FEATURE_2610_24 / D35). Every call carries the seller's own key as a
 * bearer token and a JSON body; the parsed response is returned.
 *
 * <p><b>Rules</b></p>
 * <ul>
 *   <li>D8 — {@code oklyx.lotteon.outbound-enabled=false} (dev · local) → 503 {@value #OUTBOUND_DISABLED_MESSAGE}
 *       before any network I/O. No exception for category lookups (D10).</li>
 *   <li>D23 · D30 — an HTTP error (429 included), or a body whose {@code returnCode} is not {@code 0000}, becomes a
 *       502 whose message is LotteON's own text ({@link #messageOf}). Per-line results ({@code data[].resultCode})
 *       are the caller's to read ({@link #lineFailure}).</li>
 * </ul>
 *
 * <p>❌ Do not call the LotteON API from anywhere else, and never put a LotteON key getter on
 * {@code MarketplaceAccount} (read it with {@link LotteonCredentials#of}).</p>
 */
@Component
public class LotteonApiClient {

    public static final String OUTBOUND_DISABLED_MESSAGE = "개발 서버에서는 롯데ON 을 부르지 않습니다";
    static final String FALLBACK_MESSAGE_PREFIX = "롯데ON 응답 오류(HTTP ";
    static final String SUCCESS = "0000";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final LotteonProperties properties;

    public LotteonApiClient(RestClient.Builder builder, LotteonProperties properties) {
        this.restClient = builder.build();
        this.properties = properties;
    }

    public JsonNode get(String path, String apiKey) {
        return execute(HttpMethod.GET, properties.getBaseUrl() + path, null, apiKey, true);
    }

    public JsonNode post(String path, Object body, String apiKey) {
        return execute(HttpMethod.POST, properties.getBaseUrl() + path, body, apiKey, true);
    }

    /** [205] on the category host — its answer has no {@code returnCode} ({@code itemList} only). */
    public JsonNode getCategory(String pathAndQuery, String apiKey) {
        return execute(HttpMethod.GET, properties.getCategoryBaseUrl() + pathAndQuery, null, apiKey, false);
    }

    private JsonNode execute(HttpMethod method, String url, Object body, String apiKey, boolean checkReturnCode) {
        if (!properties.isOutboundEnabled()) {
            throw new BusinessException(OUTBOUND_DISABLED_MESSAGE, HttpStatus.SERVICE_UNAVAILABLE);
        }
        RestClient.RequestBodySpec request = restClient.method(method).uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        String raw;
        try {
            raw = request.retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new BusinessException(messageOf(parse(e.getResponseBodyAsString()), e.getStatusCode().value()),
                    HttpStatus.BAD_GATEWAY);
        }
        JsonNode node = parse(raw);
        if (checkReturnCode) {
            JsonNode returnCode = node.get("returnCode");
            if (returnCode != null && !returnCode.isNull() && !SUCCESS.equals(returnCode.asText())) {
                throw new BusinessException(messageOf(node, 200), HttpStatus.BAD_GATEWAY);
            }
        }
        return node;
    }

    /** LotteON's own text ({@code message}); none → 「롯데ON 응답 오류(HTTP {n})」. */
    public static String messageOf(JsonNode node, int httpStatus) {
        JsonNode message = node == null ? null : node.get("message");
        if (message != null && message.isTextual() && !message.asText().isBlank()) {
            return message.asText();
        }
        return FALLBACK_MESSAGE_PREFIX + httpStatus + ")";
    }

    /**
     * D23: the first {@code data[]} line whose {@code resultCode} is not {@code 0000} → its {@code resultMessage}
     * (else the body's {@code message}); null when every line succeeded.
     */
    public static String lineFailure(JsonNode body) {
        for (JsonNode line : body.path("data")) {
            String code = line.path("resultCode").asText(null);
            if (code != null && !SUCCESS.equals(code)) {
                String text = line.path("resultMessage").asText("");
                return text.isBlank() ? messageOf(body, 200) : text;
            }
        }
        return null;
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(raw);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }
}
