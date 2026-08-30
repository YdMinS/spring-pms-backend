package com.pms.service.listing.category;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangApiClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Coupang category meta (FEATURE_2608_06 / 47): getMeta parses data.attributes[] → CategoryAttribute (with
 * required flag + input type + SELECT options) and data.noticeCategories[] → CategoryNotice; an empty
 * response yields an empty schema (never throws). ObjectMapper is real; the HTTP client is mocked.
 */
@ExtendWith(MockitoExtension.class)
class CoupangCategoryMetaTest {

    @Mock private CoupangApiClient client;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CoupangCategoryMeta meta;

    private MarketplaceAccount acct() {
        return MarketplaceAccount.builder().vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build();
    }

    @Test
    void getMeta_parsesAttributesAndNotices() {
        given(client.get(contains("category-related-metas"), eq(""), any()))
                .willReturn(CoupangCategoryMeta.META_FIXTURE_JSON);

        CategoryMetaSchema schema = meta.getMeta(acct(), "1001");

        // 94: inputType comes from the response field, not from the (nonexistent) basicUnits[].
        assertThat(schema.attributes())
                .extracting(CategoryAttribute::name, CategoryAttribute::required, CategoryAttribute::inputType)
                .contains(tuple("최소 중량", true, "NUMBER"),
                        tuple("식품 프리미엄", false, "SELECT"),
                        tuple("동물종류", false, "TEXT"));
        assertThat(schema.attributes()).filteredOn(a -> a.name().equals("식품 프리미엄"))
                .singleElement()
                .extracting(CategoryAttribute::options).asList()
                .containsExactly("Y", "해당없음");
        assertThat(schema.attributes())
                .extracting(CategoryAttribute::name, CategoryAttribute::basicUnit)
                // "없음" is a literal string in the response — it must normalize to null.
                .contains(tuple("최소 중량", "g"), tuple("식품 프리미엄", null), tuple("동물종류", null));
        // ⑤: groupNumber 파싱 — 실응답의 "1" 은 그대로, 그룹 없음 리터럴 "NONE" 은 null 로 정규화.
        assertThat(schema.attributes())
                .extracting(CategoryAttribute::name, CategoryAttribute::groupNumber)
                .contains(tuple("최소 중량", "1"), tuple("식품 프리미엄", null), tuple("동물종류", null));
        assertThat(schema.attributes()).filteredOn(a -> a.name().equals("최소 중량"))
                .singleElement()
                .matches(CategoryAttribute::grouped, "택1 그룹에 속함");
        // 61: every detail keeps its parent noticeCategoryName as groupName.
        assertThat(schema.notices())
                .extracting(CategoryNotice::key, CategoryNotice::required, CategoryNotice::groupName)
                .contains(tuple("제품명", true, "가공식품"),
                        tuple("소비자상담관련 전화번호", true, "가공식품"),
                        tuple("생산자(수입자)", true, "농수축산물"));
    }

    @Test
    void getMeta_optionalNotice_parsedAsNotRequired() {
        // The verified category (72882) has no OPTIONAL notice at all, so the required=false branch
        // lives here rather than in the fixture — the fixture stays faithful to the real response.
        String json = "{\"code\":200,\"data\":{\"noticeCategories\":["
                + "{\"noticeCategoryName\":\"기타 재화\",\"noticeCategoryDetailNames\":["
                + "{\"noticeCategoryDetailName\":\"인증/허가 사항\",\"required\":\"OPTIONAL\"}]}]}}";
        given(client.get(contains("category-related-metas"), eq(""), any())).willReturn(json);

        CategoryMetaSchema schema = meta.getMeta(acct(), "1001");

        assertThat(schema.notices()).singleElement()
                .extracting(CategoryNotice::key, CategoryNotice::required, CategoryNotice::groupName)
                .containsExactly("인증/허가 사항", false, "기타 재화");
    }

    @Test
    void getMeta_selectWithoutOptions_downgradesToText() {
        String json = "{\"code\":200,\"data\":{\"attributes\":["
                + "{\"attributeTypeName\":\"빈 셀렉트\",\"dataType\":\"STRING\",\"inputType\":\"SELECT\","
                + "\"inputValues\":[],\"basicUnit\":\"없음\",\"usableUnits\":[],"
                + "\"required\":\"OPTIONAL\",\"groupNumber\":\"NONE\",\"exposed\":\"NONE\"}]}}";
        given(client.get(contains("category-related-metas"), eq(""), any())).willReturn(json);

        CategoryMetaSchema schema = meta.getMeta(acct(), "1001");

        assertThat(schema.attributes()).singleElement()
                .extracting(CategoryAttribute::inputType, CategoryAttribute::options)
                .containsExactly("TEXT", List.of());
    }

    @Test
    void getMeta_emptyResponse_returnsEmptySchema() {
        given(client.get(contains("category-related-metas"), eq(""), any()))
                .willReturn("{\"code\":200,\"data\":{}}");

        CategoryMetaSchema schema = meta.getMeta(acct(), "1001");

        assertThat(schema.attributes()).isEmpty();
        assertThat(schema.notices()).isEmpty();
    }
}
