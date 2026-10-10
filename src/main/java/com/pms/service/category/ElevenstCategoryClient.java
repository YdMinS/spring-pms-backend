package com.pms.service.category;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Downloads the public 11st category tree (FEATURE_2610_10 / D7 · D21 ①).
 *
 * <p>The endpoint needs no API key, so it is the one 11st call a dev server may make (D12). The response is
 * the whole tree in one XML document (about 3 MB, EUC-KR) — the bytes are returned untouched and
 * {@link ElevenstCategoryXmlParser} reads the encoding from the XML declaration.</p>
 *
 * <p>Timeouts come from the shared {@code RestClient.Builder} customizer ({@code RestClientConfig}).</p>
 */
@Component
public class ElevenstCategoryClient {

    static final String CATEGORY_URL = "https://api.11st.co.kr/rest/cateservice/category";

    private final RestClient restClient;

    public ElevenstCategoryClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /** @return the raw XML bytes of the full 11st category tree */
    public byte[] fetchCategoryXml() {
        byte[] body = restClient.get().uri(CATEGORY_URL).retrieve().body(byte[].class);
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("11번가 카테고리 응답이 비어 있습니다.");
        }
        return body;
    }
}
