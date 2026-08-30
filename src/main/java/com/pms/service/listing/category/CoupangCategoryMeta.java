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
 * <p>Response keys are validated against the shared {@link #META_FIXTURE_JSON} (also served by
 * {@link com.pms.service.coupang.MockCoupangApiClient}), whose structure mirrors the real-account response
 * verified on 2026-08-29 (code=72882, 94).</p>
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
     * Shared meta fixture (§3): 실계정 응답(2026-08-29, code=72882) 구조를 축약 — attributes 3개(각 분기 1개:
     * 최소 중량 = MANDATORY·NUMBER·단위 g, 식품 프리미엄 = SELECT·후보 2개, 동물종류 = INPUT·STRING)와
     * noticeCategories 2 그룹(⚠️ 이 카테고리의 고시는 <b>전부 MANDATORY</b> — OPTIONAL 고시를 지어내지 말 것;
     * required=false 분기는 테스트 로컬 JSON 이 덮는다). Reused by the Mock client and the adapter test.
     *
     * <p>⚠️ <b>키는 실응답 그대로 유지할 것</b> — 파싱하지 않는 {@code usableUnits}/{@code groupNumber}/
     * {@code exposed} 도 남긴다(응답에 없는 필드와 우리가 안 읽는 필드를 구분하기 위해). 값을 지어내지 말 것:
     * 가짜 구조의 픽스처가 94 이전 attributes 파싱 결함의 원인이었다.</p>
     */
    public static final String META_FIXTURE_JSON =
            "{\"code\":200,\"data\":{"
                    + "\"attributes\":["
                    + "{\"attributeTypeName\":\"최소 중량\",\"dataType\":\"NUMBER\",\"inputType\":\"INPUT\","
                    + "\"inputValues\":[],\"basicUnit\":\"g\",\"usableUnits\":[\"g\",\"kg\",\"mg\"],"
                    + "\"required\":\"MANDATORY\",\"groupNumber\":\"1\",\"exposed\":\"EXPOSED\"},"
                    + "{\"attributeTypeName\":\"식품 프리미엄\",\"dataType\":\"STRING\",\"inputType\":\"SELECT\","
                    + "\"inputValues\":[\"Y\",\"해당없음\"],\"basicUnit\":\"없음\",\"usableUnits\":[],"
                    + "\"required\":\"OPTIONAL\",\"groupNumber\":\"NONE\",\"exposed\":\"NONE\"},"
                    + "{\"attributeTypeName\":\"동물종류\",\"dataType\":\"STRING\",\"inputType\":\"INPUT\","
                    + "\"inputValues\":[],\"basicUnit\":\"없음\",\"usableUnits\":[],"
                    + "\"required\":\"OPTIONAL\",\"groupNumber\":\"NONE\",\"exposed\":\"NONE\"}],"
                    + "\"noticeCategories\":["
                    + "{\"noticeCategoryName\":\"가공식품\",\"noticeCategoryDetailNames\":["
                    + "{\"noticeCategoryDetailName\":\"제품명\",\"required\":\"MANDATORY\"},"
                    + "{\"noticeCategoryDetailName\":\"소비자상담관련 전화번호\",\"required\":\"MANDATORY\"}]},"
                    + "{\"noticeCategoryName\":\"농수축산물\",\"noticeCategoryDetailNames\":["
                    + "{\"noticeCategoryDetailName\":\"생산자(수입자)\",\"required\":\"MANDATORY\"}]}]}}";

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
            // SELECT candidates live in inputValues[] as plain strings (94 — real-account keys, 2026-08-29).
            List<String> options = new ArrayList<>();
            for (JsonNode value : attr.path("inputValues")) {
                String option = value.asText(null);
                if (option != null && !option.isBlank()) {
                    options.add(option);
                }
            }
            attributes.add(new CategoryAttribute(
                    attr.path("attributeTypeName").asText(null),
                    "MANDATORY".equals(attr.path("required").asText("")),
                    inputType(attr.path("inputType").asText(""), attr.path("dataType").asText(""),
                            !options.isEmpty()),
                    options,
                    basicUnit(attr.path("basicUnit").asText(null)),
                    groupNumber(attr.path("groupNumber").asText(null))));
        }
        return attributes;
    }

    /**
     * 택1 그룹 번호. 그룹이 없으면 쿠팡이 리터럴 {@code "NONE"} 을 주므로 null 로 정규화한다
     * (⑤ — 같은 번호의 MANDATORY 속성은 그룹 중 하나만 채우면 충족).
     */
    private String groupNumber(String raw) {
        if (raw == null || raw.isBlank() || "NONE".equals(raw)) {
            return null;
        }
        return raw;
    }

    /** Coupang writes the literal string {@code "없음"} when an attribute has no unit — normalize it to null. */
    private String basicUnit(String raw) {
        if (raw == null || raw.isBlank() || "없음".equals(raw)) {
            return null;
        }
        return raw;
    }

    private List<CategoryNotice> parseNotices(JsonNode data) {
        // Multiple noticeCategories are possible (a category may cover several 품목); flatten every detail into
        // one list but keep each one's parent noticeCategoryName as groupName (61 — the front groups by it).
        List<CategoryNotice> notices = new ArrayList<>();
        for (JsonNode noticeCategory : data.path("noticeCategories")) {
            String group = noticeCategory.path("noticeCategoryName").asText(null);
            for (JsonNode detail : noticeCategory.path("noticeCategoryDetailNames")) {
                String key = detail.path("noticeCategoryDetailName").asText(null);
                notices.add(new CategoryNotice(
                        key, key, "MANDATORY".equals(detail.path("required").asText("")), group));
            }
        }
        return notices;
    }

    /**
     * 응답 {@code inputType} 을 신뢰. SELECT 인데 후보가 비면 강등(빈 드롭다운 방지 — 고를 값이 없는
     * 드롭다운은 필수 속성을 영원히 못 채운다). 그 외에는 STRING→TEXT, NUMBER→NUMBER (default TEXT).
     */
    private String inputType(String rawInputType, String dataType, boolean hasOptions) {
        if ("SELECT".equals(rawInputType) && hasOptions) {
            return "SELECT";
        }
        return "NUMBER".equals(dataType) ? "NUMBER" : "TEXT";
    }
}
