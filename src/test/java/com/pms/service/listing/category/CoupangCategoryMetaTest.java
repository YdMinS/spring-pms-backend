package com.pms.service.listing.category;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangApiClient;
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

        assertThat(schema.attributes())
                .extracting(CategoryAttribute::name, CategoryAttribute::required, CategoryAttribute::inputType)
                .contains(tuple("원산지", true, "TEXT"), tuple("사이즈", false, "SELECT"));
        assertThat(schema.attributes()).filteredOn(a -> a.name().equals("사이즈"))
                .singleElement()
                .extracting(CategoryAttribute::options).asList()
                .containsExactly("S", "M", "L");
        assertThat(schema.notices())
                .extracting(CategoryNotice::key, CategoryNotice::required)
                .contains(tuple("제품소재", true), tuple("제조자", false));
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
