package com.pms.service.listing.category;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Coupang {@link CategoryLookup} implementation (FEATURE_2608_06 / 45) — reuses {@link CoupangApiClient}
 * (account HMAC) for both calls; no new client method needed.
 *
 * <p>⚠️ Response keys ({@code displayCategoryCode}/{@code name}/{@code child}/{@code last}, and predict's
 * {@code predictedCategoryId}/{@code categoryName}) are validated against the local
 * {@link com.pms.service.coupang.MockCoupangApiClient} fixture — real-account key hardening is follow-up.</p>
 */
@Component
@RequiredArgsConstructor
public class CoupangCategoryLookup implements CategoryLookup {

    private static final Logger log = LoggerFactory.getLogger(CoupangCategoryLookup.class);

    private static final String DISPLAY_CATEGORIES =
            "/v2/providers/seller_api/apis/api/v1/marketplace/meta/display-categories/";
    private static final String PREDICT =
            "/v2/providers/seller_api/apis/api/v1/categorization/predict";

    private final CoupangApiClient client;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return "COUPANG";
    }

    @Override
    public List<CategoryNode> browse(MarketplaceAccount account, String parentCode) {
        String code = StringUtils.hasText(parentCode) ? parentCode : "0";   // null/blank = root
        String raw = client.get(DISPLAY_CATEGORIES + code, "", account);
        JsonNode data = readJson(raw).path("data");

        List<CategoryNode> nodes = new ArrayList<>();
        for (JsonNode child : data.path("child")) {
            // leaf = no children OR the market flags it as a leaf (last=true).
            boolean leaf = child.path("last").asBoolean(false) || !child.path("child").elements().hasNext();
            nodes.add(new CategoryNode(
                    child.path("displayCategoryCode").asText(null),
                    child.path("name").asText(null),
                    leaf));
        }
        return nodes;
    }

    @Override
    public List<CategorySuggestion> predict(MarketplaceAccount account, String productName) {
        String body = writeJson(new PredictRequest(productName));
        String raw;
        try {
            raw = client.post(PREDICT, body, account);
        } catch (RuntimeException e) {
            log.warn("[COUPANG-CATEGORY] predict call failed: {}", e.getMessage());
            return List.of();       // no candidate is a normal result — never fail the caller
        }

        JsonNode data;
        try {
            data = objectMapper.readTree(raw).path("data");
        } catch (Exception e) {
            log.warn("[COUPANG-CATEGORY] predict parse failed: {}", e.getMessage());
            return List.of();
        }

        String categoryId = asTextOrNull(data, "predictedCategoryId");
        if (!StringUtils.hasText(categoryId)) {
            return List.of();       // empty / missing prediction → no candidate
        }
        String name = data.path("categoryName").asText(null);
        // Coupang predict returns a single candidate; no separate path → namePath best-effort = name.
        return List.of(new CategorySuggestion(categoryId, name, name));
    }

    private record PredictRequest(String productName) {
    }

    private static String asTextOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** JSON parse failure on browse = client error (→ 400). */
    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("쿠팡 카테고리 응답 파싱 실패", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("쿠팡 카테고리 요청 직렬화 실패", e);
        }
    }
}
