package com.pms.service.esm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.EsmProperties;
import com.pms.exception.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.time.Instant;

/**
 * The single client for the ESM (Gmarket · Auction) Trading API (FEATURE_2610_26 / D42). Every call carries a
 * fresh bearer token ({@link EsmTokens}, D6) and a JSON body; the parsed response is returned.
 *
 * <p><b>Rules</b></p>
 * <ul>
 *   <li>D10 — {@code oklyx.esm.outbound-enabled=false} (dev · local) → 503 {@value #OUTBOUND_DISABLED_MESSAGE}
 *       before any network I/O. No exception for category lookups.</li>
 *   <li>D25 · D38 — an HTTP error, or a body whose {@code resultCode} is not 0, becomes a 502 whose message is
 *       ESM's own text ({@link #messageOf}).</li>
 * </ul>
 *
 * <p>❌ Do not call the ESM API from anywhere else, and never put an ESM key getter on {@code MarketplaceAccount}
 * (read it with {@link EsmCredentials#of}).</p>
 */
@Component
public class EsmApiClient {

    static final String OUTBOUND_DISABLED_MESSAGE = "개발 서버에서는 G마켓 · 옥션을 부르지 않습니다";
    static final String FALLBACK_MESSAGE_PREFIX = "G마켓 · 옥션 응답 오류(HTTP ";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final EsmProperties properties;

    public EsmApiClient(RestClient.Builder builder, EsmProperties properties) {
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
    }

    public JsonNode get(String path, EsmKey key) {
        return execute(HttpMethod.GET, path, null, key, EsmSite.BOTH);
    }

    /** D6: the two lookups that take one site seller ID only (site categories · recommended options). */
    public JsonNode get(String path, EsmKey key, EsmSite site) {
        return execute(HttpMethod.GET, path, null, key, site);
    }

    public JsonNode post(String path, Object body, EsmKey key) {
        return execute(HttpMethod.POST, path, body, key, EsmSite.BOTH);
    }

    public JsonNode put(String path, Object body, EsmKey key) {
        return execute(HttpMethod.PUT, path, body, key, EsmSite.BOTH);
    }

    private JsonNode execute(HttpMethod method, String path, Object body, EsmKey key, EsmSite site) {
        if (!properties.isOutboundEnabled()) {
            throw new BusinessException(OUTBOUND_DISABLED_MESSAGE, HttpStatus.SERVICE_UNAVAILABLE);
        }
        String token = EsmTokens.token(key, site, Instant.now().getEpochSecond());
        RestClient.RequestBodySpec request = restClient.method(method).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
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
        JsonNode resultCode = node.get("resultCode");
        if (resultCode != null && !resultCode.isNull() && resultCode.asInt() != 0) {
            throw new BusinessException(messageOf(node, 200), HttpStatus.BAD_GATEWAY);
        }
        return node;
    }

    /**
     * ESM's own text: {@code message} (most posts) · {@code Message} (post 18) · {@code status.message} (the
     * authentication gate, guide example). None → 「G마켓 · 옥션 응답 오류(HTTP {n})」.
     */
    public static String messageOf(JsonNode node, int httpStatus) {
        for (JsonNode candidate : new JsonNode[]{node.get("message"), node.get("Message"),
                node.path("status").get("message")}) {
            if (candidate != null && candidate.isTextual() && !candidate.asText().isBlank()) {
                return candidate.asText();
            }
        }
        return FALLBACK_MESSAGE_PREFIX + httpStatus + ")";
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
