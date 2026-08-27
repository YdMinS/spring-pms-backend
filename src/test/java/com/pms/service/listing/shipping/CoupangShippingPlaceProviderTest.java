package com.pms.service.listing.shipping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Coupang shipping-place provider (FEATURE_2608_06 / 72): outbound parses data.content[] → OutboundPlace,
 * return parses the full address block → ReturnCenter, and an empty/failed response → empty list (manual entry
 * applies). ObjectMapper is real; the HTTP client is mocked.
 */
@ExtendWith(MockitoExtension.class)
class CoupangShippingPlaceProviderTest {

    @Mock private CoupangApiClient client;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CoupangShippingPlaceProvider provider;

    private MarketplaceAccount acct() {
        return MarketplaceAccount.builder()
                .vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build();
    }

    @Test
    void fetchOutboundPlaces_parsesContentArray() {
        given(client.get(contains("outboundShippingCenters"), eq(""), any())).willReturn(
                "{\"code\":200,\"data\":{\"content\":["
                        + "{\"outboundShippingPlaceCode\":\"74010\",\"shippingPlaceName\":\"기본출고지\"},"
                        + "{\"outboundShippingPlaceCode\":\"74011\",\"shippingPlaceName\":\"제2출고지\"}"
                        + "]}}");

        List<OutboundPlace> places = provider.fetchOutboundPlaces(acct());

        assertThat(places).extracting(OutboundPlace::code, OutboundPlace::name)
                .containsExactly(tuple("74010", "기본출고지"), tuple("74011", "제2출고지"));
    }

    @Test
    void fetchReturnCenters_parsesFullAddressBlock() {
        given(client.get(contains("returnShippingCenters"), eq(""), any())).willReturn(
                "{\"code\":200,\"data\":{\"content\":[{"
                        + "\"returnCenterCode\":\"1000274592\",\"shippingPlaceName\":\"기본반품지\","
                        + "\"returnFee\":\"2500\",\"deliveryFee\":\"3000\",\"placeAddresses\":[{"
                        + "\"companyContactName\":\"홍길동\",\"companyContactNumber\":\"02-1234-5678\","
                        + "\"returnZipCode\":\"06000\",\"returnAddress\":\"서울시 강남구 테헤란로 1\","
                        + "\"returnAddressDetail\":\"3층\"}]}]}}");

        List<ReturnCenter> centers = provider.fetchReturnCenters(acct());

        assertThat(centers).hasSize(1);
        ReturnCenter c = centers.get(0);
        assertThat(c.code()).isEqualTo("1000274592");
        assertThat(c.name()).isEqualTo("기본반품지");
        assertThat(c.chargeName()).isEqualTo("홍길동");
        assertThat(c.contactNumber()).isEqualTo("02-1234-5678");
        assertThat(c.zipCode()).isEqualTo("06000");
        assertThat(c.address()).isEqualTo("서울시 강남구 테헤란로 1");
        assertThat(c.addressDetail()).isEqualTo("3층");
        assertThat(c.returnCharge()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(c.deliveryChargeOnReturn()).isEqualByComparingTo(new BigDecimal("3000"));
    }

    @Test
    void fetchOutboundPlaces_emptyResponse_returnsEmptyList() {
        given(client.get(contains("outboundShippingCenters"), eq(""), any()))
                .willReturn("{\"code\":200,\"data\":{}}");

        assertThat(provider.fetchOutboundPlaces(acct())).isEmpty();
    }
}
