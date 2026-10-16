package com.pms.service.elevenst;

import com.pms.config.ElevenstProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;

/**
 * Live client for the keyed 11st seller API (FEATURE_2610_13). Every call sends the account's key in the
 * {@code openapikey} header (D5) and an EUC-KR XML body; the raw response bytes are returned untouched
 * (callers read them with {@link ElevenstXml}).
 *
 * <p><b>Rules</b></p>
 * <ul>
 *   <li>D12 — {@code oklyx.elevenst.outbound-enabled=false} (dev · local, D116) → 503 「개발 서버에서는 11번가를 부르지 않습니다」
 *       before any network I/O.</li>
 *   <li>D60 — an HTTP error from 11st becomes a 502 whose message is 11st's own {@code message} text.</li>
 *   <li>D88 — every successful call stamps the account's key-use time ({@link ElevenstKeyUsageRecorder}).</li>
 * </ul>
 *
 * <p>❌ Do not call 11st's keyed API from anywhere else. The public category download
 * ({@code ElevenstCategoryClient}) needs no key and stays separate.</p>
 */
@Component
public class ElevenstApiClient {

    static final String OUTBOUND_DISABLED_MESSAGE = "개발 서버에서는 11번가를 부르지 않습니다";
    static final MediaType XML_EUC_KR = MediaType.parseMediaType("text/xml;charset=EUC-KR");

    private final RestClient restClient;
    private final ElevenstProperties properties;
    private final ElevenstKeyUsageRecorder keyUsageRecorder;

    public ElevenstApiClient(RestClient.Builder builder, ElevenstProperties properties,
                             ElevenstKeyUsageRecorder keyUsageRecorder) {
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
        this.keyUsageRecorder = keyUsageRecorder;
    }

    public byte[] get(String path, MarketplaceAccount account) {
        return execute(HttpMethod.GET, path, null, null, account);
    }

    /**
     * GET with a path whose segments are already percent-encoded (FEATURE_2610_17 / D39 ② — reason text sent as
     * EUC-KR URL-encoded path segments). {@link #get} would encode the {@code %} a second time.
     */
    public byte[] getPreEncoded(String encodedPath, MarketplaceAccount account) {
        return execute(HttpMethod.GET, null, URI.create(properties.getBaseUrl() + encodedPath), null, account);
    }

    public byte[] post(String path, String xmlBody, MarketplaceAccount account) {
        return execute(HttpMethod.POST, path, null, xmlBody, account);
    }

    public byte[] put(String path, String xmlBody, MarketplaceAccount account) {
        return execute(HttpMethod.PUT, path, null, xmlBody, account);
    }

    private byte[] execute(HttpMethod method, String path, URI encodedUri, String xmlBody,
                           MarketplaceAccount account) {
        if (!properties.isOutboundEnabled()) {
            throw new BusinessException(OUTBOUND_DISABLED_MESSAGE, HttpStatus.SERVICE_UNAVAILABLE);
        }
        String apiKey = ElevenstCredentials.of(account).getApiKey();
        RestClient.RequestBodyUriSpec spec = restClient.method(method);
        RestClient.RequestBodySpec request = (encodedUri != null ? spec.uri(encodedUri) : spec.uri(path))
                .header("openapikey", apiKey);
        if (xmlBody != null) {
            request = request.contentType(XML_EUC_KR).body(ElevenstXml.document(xmlBody));
        }
        byte[] body;
        try {
            body = request.retrieve().body(byte[].class);
        } catch (RestClientResponseException e) {
            throw new BusinessException("11번가 응답 오류(HTTP " + e.getStatusCode().value() + "): "
                    + ElevenstXml.messageOf(e.getResponseBodyAsByteArray()), HttpStatus.BAD_GATEWAY);
        }
        keyUsageRecorder.touch(account.getId());
        return body == null ? new byte[0] : body;
    }
}
