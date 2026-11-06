package com.pms.service.listing.shipping;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.exception.BusinessException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.lotteon.LotteonApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/** FEATURE_2610_24 / D42 · D65: input keys only; the picked policy's fee facts are written by the server. */
@ExtendWith(MockitoExtension.class)
class LotteonShippingPoliciesTest {

    @Mock private LotteonShippingLookups lookups;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @InjectMocks private LotteonShippingPolicies policies;

    private final MarketplaceAccount account = MarketplaceAccount.builder().id(5L).platform(Platform.LOTTEON).build();

    private static Map<String, String> raw(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void forChannel_writesThePoliciesFacts_andDropsClientAmounts() {
        given(lookups.feePolicies(account)).willReturn(List.of(
                new LotteonShippingLookups.FeePolicy("DLD1", "기본", "C", new BigDecimal("3000.00"),
                        new BigDecimal("30000.00"))));

        Map<String, String> saved = policies.forChannel(account, raw(
                LotteonShippingKeys.OWHP_NO, "PLO1", LotteonShippingKeys.RTRP_NO, "PLO2",
                LotteonShippingKeys.DV_CST_POL_NO, "DLD1", LotteonShippingKeys.DV_CST, "1", "other", "x"));

        assertThat(saved).containsExactly(
                Map.entry(LotteonShippingKeys.OWHP_NO, "PLO1"),
                Map.entry(LotteonShippingKeys.RTRP_NO, "PLO2"),
                Map.entry(LotteonShippingKeys.DV_CST_POL_NO, "DLD1"),
                Map.entry(LotteonShippingKeys.DV_CST_DVS_CD, "C"),
                Map.entry(LotteonShippingKeys.DV_CST, "3000"),
                Map.entry(LotteonShippingKeys.CNDL_FREE_STD_AMT, "30000"));
    }

    @Test
    void forChannel_policyNotOnLotteon_is400() {
        given(lookups.feePolicies(account)).willReturn(List.of());

        assertThatThrownBy(() -> policies.forChannel(account, raw(LotteonShippingKeys.DV_CST_POL_NO, "DLD9")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("고른 배송비 정책이 롯데ON 에 없습니다");
    }

    @Test
    void forChannel_withoutPolicy_doesNotCallLotteon() {
        Map<String, String> saved = policies.forChannel(account, raw(LotteonShippingKeys.OWHP_NO, "PLO1"));

        assertThat(saved).containsExactly(Map.entry(LotteonShippingKeys.OWHP_NO, "PLO1"));
        verify(lookups, never()).feePolicies(any());
    }

    @Test
    void forListing_aServerThatDoesNotCallLotteon_answers503() {
        ProductListing cell = ProductListing.builder().id(1L).platform(Platform.LOTTEON)
                .seller(Seller.builder().id(7L).build()).build();
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.LOTTEON))
                .willReturn(Optional.of(account));
        given(lookups.feePolicies(account)).willThrow(new BusinessException(
                LotteonApiClient.OUTBOUND_DISABLED_MESSAGE, HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> policies.forListing(cell, raw(LotteonShippingKeys.DV_CST_POL_NO, "DLD1")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("개발 서버에서는 롯데ON 을 부르지 않습니다");
    }

    @Test
    void forListing_nothingLeft_isNull() {
        ProductListing cell = ProductListing.builder().id(1L).platform(Platform.LOTTEON).build();

        assertThat(policies.forListing(cell, raw("lotteon.dvCst", "1"))).isNull();
    }
}
