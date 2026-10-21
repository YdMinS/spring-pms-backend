package com.pms.service.esm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.fixture.MarketplaceAccountFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/** FEATURE_2610_26 / D37 · D68: list names and paging. */
@ExtendWith(MockitoExtension.class)
class EsmShippingLookupsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private EsmApiClient client;
    @InjectMocks private EsmShippingLookups lookups;

    private final MarketplaceAccount account =
            MarketplaceAccountFixture.esmStubBuilder("m", "s", "g", "a").id(3L).build();

    @Test
    void bundleNames_followD37() {
        assertThat(EsmShippingLookups.bundleName("1", BigDecimal.ZERO, null, "1")).isEqualTo("무료");
        assertThat(EsmShippingLookups.bundleName("2", new BigDecimal("2500.0"), null, "2")).isEqualTo("유료 2500원");
        assertThat(EsmShippingLookups.bundleName("3", new BigDecimal("2500"), new BigDecimal("20000"), "3"))
                .isEqualTo("조건부 2500원 (20000원 이상 무료)");
    }

    @Test
    void dispatchNames_policyNameWithNumber_orTheTypeName() throws Exception {
        given(client.get(eq("/item/v1/shipping/dispatch-policies"), any())).willReturn(JSON.readTree(
                "{\"dispatchPolicies\":[{\"dispatchPolicyNo\":910,\"dispatchPolicyName\":\"당일발송\","
                        + "\"dispatchType\":\"A\"},{\"dispatchPolicyNo\":911,\"dispatchPolicyName\":\"\","
                        + "\"dispatchType\":\"B\"}]}"));

        assertThat(lookups.dispatchPolicies(account)).extracting(EsmShippingLookups.Choice::name)
                .containsExactly("당일발송 (910)", "순차발송 (911)");
    }

    @Test
    void shippingPlaces_readsPagesUntilAShortOne() throws Exception {
        StringBuilder full = new StringBuilder("{\"shippingPlaces\":[");
        for (int i = 0; i < 500; i++) {
            full.append(i == 0 ? "" : ",").append("{\"placeNo\":").append(i).append('}');
        }
        full.append("]}");
        given(client.get(eq("/item/v1/shipping/places?pageSize=500&pageIndex=1"), any()))
                .willReturn(JSON.readTree(full.toString()));
        given(client.get(eq("/item/v1/shipping/places?pageSize=500&pageIndex=2"), any()))
                .willReturn(JSON.readTree("{\"shippingPlaces\":[{\"placeNo\":900}]}"));

        assertThat(lookups.shippingPlaces(account)).hasSize(501);
    }
}
