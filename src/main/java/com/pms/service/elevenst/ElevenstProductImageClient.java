package com.pms.service.elevenst;

import com.pms.config.ElevenstProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 11st public OpenAPI 「상품이미지검색」 (FEATURE_2611_11 / D35) — the only way to read an 11st product's photos (the
 * seller API returns none, discussion §2-11). Read only.
 *
 * <p>{@code GET {openApiUrl}?key={key}&apiCode=ProductImage&productCode={prdNo}} → {@code <Product>} with
 * {@code BasicImage} (main) and {@code AddImage1} ~ {@code AddImage3} (additional). Field names are the guide's; the
 * live answer is first checked on the production server (D20).</p>
 *
 * <p>🔴 Every failure throws {@link ElevenstPhotoReadException} — a missing switch, a network error, an
 * {@code <ErrorResponse>} ({@code 004 overedTraffic} = the daily limit) or an answer without {@code BasicImage}. The
 * caller turns it into the D35 guidance and keeps the listing's photos.</p>
 */
@Component
public class ElevenstProductImageClient {

    private static final List<String> ADDITIONAL_FIELDS = List.of("AddImage1", "AddImage2", "AddImage3");

    private final RestClient restClient;
    private final ElevenstProperties properties;

    public ElevenstProductImageClient(RestClient.Builder builder, ElevenstProperties properties) {
        this.restClient = builder.build();
        this.properties = properties;
    }

    /**
     * @param productNo the 11st product number ({@code prdNo})
     * @param key       the company's photo lookup key (D39)
     * @return the main photo and the additional photos in the guide's order (absent ones skipped)
     */
    public Photos fetch(String productNo, String key) {
        if (!properties.isOutboundEnabled()) {
            throw new ElevenstPhotoReadException(ElevenstApiClient.OUTBOUND_DISABLED_MESSAGE);
        }
        URI uri = UriComponentsBuilder.fromUriString(properties.getOpenApiUrl())
                .queryParam("key", key)
                .queryParam("apiCode", "ProductImage")
                .queryParam("productCode", productNo)
                .build()
                .encode()
                .toUri();
        byte[] body;
        try {
            body = restClient.get().uri(uri).retrieve().body(byte[].class);
        } catch (Exception e) {
            throw new ElevenstPhotoReadException("11st photo read failed: " + e.getMessage());
        }
        return parse(body);
    }

    /** The {@code <Product>} answer → photos; an {@code <ErrorResponse>} or no main photo throws. */
    static Photos parse(byte[] body) {
        Document doc = ElevenstXml.parseOrNull(body);
        String errorCode = ElevenstXml.firstText(doc, "ErrorCode");
        if (errorCode != null) {
            throw new ElevenstPhotoReadException("11st photo read error " + errorCode + ": "
                    + ElevenstXml.firstText(doc, "ErrorMessage"));
        }
        String basic = ElevenstXml.firstText(doc, "BasicImage");
        if (!StringUtils.hasText(basic)) {
            throw new ElevenstPhotoReadException("11st photo read answered without BasicImage");
        }
        List<String> additional = new ArrayList<>();
        for (String field : ADDITIONAL_FIELDS) {
            String url = ElevenstXml.firstText(doc, field);
            if (StringUtils.hasText(url)) {
                additional.add(url);
            }
        }
        return new Photos(basic, additional);
    }

    /**
     * @param basic      {@code BasicImage}
     * @param additional {@code AddImage1} ~ {@code AddImage3} that are present, in that order
     */
    public record Photos(String basic, List<String> additional) {
    }
}
