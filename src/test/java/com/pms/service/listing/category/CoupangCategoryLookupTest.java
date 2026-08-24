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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Coupang category lookup (FEATURE_2608_06 / 45): browse parses data.child[] → CategoryNode (with leaf flag),
 * predict parses data.predictedCategoryId/categoryName → a single CategorySuggestion (empty response → empty).
 * ObjectMapper is real; the HTTP client is mocked.
 */
@ExtendWith(MockitoExtension.class)
class CoupangCategoryLookupTest {

    @Mock private CoupangApiClient client;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CoupangCategoryLookup lookup;

    private MarketplaceAccount acct() {
        return MarketplaceAccount.builder().vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build();
    }

    private static final String TREE_JSON =
            "{\"code\":200,\"data\":{\"displayCategoryCode\":\"0\",\"name\":\"루트\",\"child\":["
                    + "{\"displayCategoryCode\":\"1001\",\"name\":\"패션의류잡화\",\"last\":false,"
                    + "\"child\":[{\"displayCategoryCode\":\"2001\",\"name\":\"여성의류\",\"last\":true}]},"
                    + "{\"displayCategoryCode\":\"1002\",\"name\":\"여성 반팔티\",\"last\":true,\"child\":[]}"
                    + "]}}";

    @Test
    void browse_root_parsesChildrenWithLeafFlag() {
        given(client.get(contains("display-categories/0"), eq(""), any())).willReturn(TREE_JSON);

        List<CategoryNode> nodes = lookup.browse(acct(), null);   // null parentCode = root → code 0

        assertThat(nodes).extracting(CategoryNode::platformCategoryId).contains("1001", "1002");
        // 1001 has a nested child (last=false) → non-leaf; 1002 empty child → leaf.
        assertThat(nodes).extracting(CategoryNode::platformCategoryId, CategoryNode::leaf)
                .contains(tuple("1001", false), tuple("1002", true));
    }

    @Test
    void predict_singleCandidate_parsesCodeAndName() {
        given(client.post(contains("categorization/predict"), contains("productName"), any()))
                .willReturn("{\"code\":\"SUCCESS\",\"data\":{\"predictedCategoryId\":\"56174\","
                        + "\"categoryName\":\"여성 반팔티\"}}");

        List<CategorySuggestion> suggestions = lookup.predict(acct(), "여성 반팔티");

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).platformCategoryId()).isEqualTo("56174");
        assertThat(suggestions.get(0).name()).isEqualTo("여성 반팔티");
    }

    @Test
    void predict_emptyResponse_returnsEmptyList() {
        given(client.post(contains("categorization/predict"), contains("productName"), any()))
                .willReturn("{\"code\":200,\"data\":{}}");

        assertThat(lookup.predict(acct(), "없는상품")).isEmpty();
    }
}
