package com.pms.service.listing.category;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Coupang {@link CategoryMetaAdapter} implementation (FEATURE_2608_06 / 47) — reuses {@link CoupangApiClient}
 * (account HMAC); no new client method. Mirrors {@code CoupangCategoryLookup}: {@code readTree(raw).path("data")}.
 *
 * <p>⚠️ Response keys are validated against the shared {@link #META_FIXTURE_JSON} (also served by
 * {@link com.pms.service.coupang.MockCoupangApiClient}) — real-account key hardening is follow-up.</p>
 *
 * <p><b>Empty-tolerant</b>: a parse failure or empty response yields an empty {@link CategoryMetaSchema}
 * (never an exception) — an empty schema is a first-class case (§5, mirrors predict's {@code List.of()}).</p>
 */
@Component
@RequiredArgsConstructor
public class CoupangCategoryMeta implements CategoryMetaAdapter {

    private static final Logger log = LoggerFactory.getLogger(CoupangCategoryMeta.class);

    private static final String CATEGORY_RELATED_METAS =
            "/v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/"
                    + "display-category-codes/";

    /**
     * Shared meta fixture (§3): 2 attributes (원산지 = required·TEXT·no options, 사이즈 = optional·SELECT·[S,M,L])
     * and 2 notices (제품소재 = required, 제조자 = optional). Reused by the Mock client and the adapter test.
     */
    public static final String META_FIXTURE_JSON =
            "{\"code\":200,\"data\":{"
                    + "\"attributes\":["
                    + "{\"attributeTypeName\":\"원산지\",\"required\":\"MANDATORY\",\"dataType\":\"STRING\","
                    + "\"basicUnits\":[]},"
                    + "{\"attributeTypeName\":\"사이즈\",\"required\":\"OPTIONAL\",\"dataType\":\"STRING\","
                    + "\"basicUnits\":[{\"unit\":\"S\"},{\"unit\":\"M\"},{\"unit\":\"L\"}]}],"
                    + "\"noticeCategories\":["
                    + "{\"noticeCategoryName\":\"의류\",\"noticeCategoryDetailNames\":["
                    + "{\"noticeCategoryDetailName\":\"제품소재\",\"required\":\"MANDATORY\"},"
                    + "{\"noticeCategoryDetailName\":\"제조자\",\"required\":\"OPTIONAL\"}]}]}}";

    private final CoupangApiClient client;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return "COUPANG";
    }

    @Override
    public CategoryMetaSchema getMeta(MarketplaceAccount account, String categoryCode) {
        try {
            String raw = client.get(CATEGORY_RELATED_METAS + categoryCode, "", account);
            JsonNode data = objectMapper.readTree(raw).path("data");
            return new CategoryMetaSchema(parseAttributes(data), parseNotices(data));
        } catch (Exception e) {
            // Empty-tolerant: never fail the caller on a parse/empty response — no required meta is a valid state.
            log.warn("[COUPANG-META] getMeta parse failed for code={}: {}", categoryCode, e.getMessage());
            return new CategoryMetaSchema(List.of(), List.of());
        }
    }

    private List<CategoryAttribute> parseAttributes(JsonNode data) {
        List<CategoryAttribute> attributes = new ArrayList<>();
        for (JsonNode attr : data.path("attributes")) {
            List<String> options = new ArrayList<>();
            for (JsonNode unit : attr.path("basicUnits")) {
                String value = unit.path("unit").asText(null);
                if (value != null) {
                    options.add(value);
                }
            }
            attributes.add(new CategoryAttribute(
                    attr.path("attributeTypeName").asText(null),
                    "MANDATORY".equals(attr.path("required").asText("")),
                    inputType(attr.path("dataType").asText(""), options),
                    options));
        }
        return attributes;
    }

    private List<CategoryNotice> parseNotices(JsonNode data) {
        List<CategoryNotice> notices = new ArrayList<>();
        for (JsonNode noticeCategory : data.path("noticeCategories")) {
            for (JsonNode detail : noticeCategory.path("noticeCategoryDetailNames")) {
                String key = detail.path("noticeCategoryDetailName").asText(null);
                notices.add(new CategoryNotice(
                        key, key, "MANDATORY".equals(detail.path("required").asText(""))));
            }
        }
        return notices;
    }

    /** basicUnits present → SELECT; else STRING→TEXT, NUMBER→NUMBER (default TEXT). */
    private String inputType(String dataType, List<String> options) {
        if (!options.isEmpty()) {
            return "SELECT";
        }
        return "NUMBER".equals(dataType) ? "NUMBER" : "TEXT";
    }
}
