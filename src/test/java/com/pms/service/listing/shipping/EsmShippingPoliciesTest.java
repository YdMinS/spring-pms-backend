package com.pms.service.listing.shipping;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.esm.EsmShippingLookups;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_26 / D36 · D60 · D66: what the ESM shipping saves keep, check and write. */
@ExtendWith(MockitoExtension.class)
class EsmShippingPoliciesTest {

    @Mock private EsmShippingLookups lookups;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @InjectMocks private EsmShippingPolicies policies;

    private final MarketplaceAccount account = MarketplaceAccount.builder().id(3L).platform(Platform.ESM).build();

    @Test
    void channel_writesTheFourFacts_andDropsClientPolicyKeys() {
        given(lookups.bundlePolicies(account, "176042")).willReturn(List.of(new EsmShippingLookups.BundlePolicy(
                "662995", "2", new BigDecimal("3000.0"), null, true, "유료 3000원")));

        Map<String, String> saved = policies.forChannel(account, Map.of(
                EsmShippingKeys.PLACE_NO, "176042", EsmShippingKeys.BUNDLE_POLICY_NO, "662995",
                EsmShippingKeys.BUNDLE_FREE_OVER, "999"));

        assertThat(saved).containsEntry(EsmShippingKeys.BUNDLE_FEE_TYPE, "2")
                .containsEntry(EsmShippingKeys.BUNDLE_FEE, "3000")
                .containsEntry(EsmShippingKeys.BUNDLE_PREPAY, "true")
                .doesNotContainKey(EsmShippingKeys.BUNDLE_FREE_OVER);
    }

    @Test
    void channel_withoutPolicy_doesNotCallEsm() {
        Map<String, String> saved = policies.forChannel(account, Map.of(EsmShippingKeys.COMPANY_NO, "10013"));

        assertThat(saved).containsOnlyKeys(EsmShippingKeys.COMPANY_NO);
        verify(lookups, never()).bundlePolicies(any(), any());
    }

    @Test
    void channel_policyWithoutPlace_isNotOfThePlace() {
        assertThatThrownBy(() -> policies.forChannel(account, Map.of(EsmShippingKeys.BUNDLE_POLICY_NO, "662995")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("고른 묶음배송비는 이 출하지의 정책이 아닙니다");
    }

    @Test
    void listing_placeWithoutBundle_rejected() {
        ProductListing cell = ProductListing.builder().id(1L).platform(Platform.ESM)
                .seller(Seller.builder().id(7L).build()).build();

        assertThatThrownBy(() -> policies.forListing(cell, Map.of(EsmShippingKeys.PLACE_NO, "176042")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("출하지와 묶음배송비는 함께 고르세요");
    }

    @Test
    void listing_bothSet_checksWithTheChannelKey() {
        ProductListing cell = ProductListing.builder().id(1L).platform(Platform.ESM)
                .seller(Seller.builder().id(7L).build()).build();
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.ESM))
                .willReturn(Optional.of(account));
        given(lookups.bundlePolicies(account, "176042")).willReturn(List.of(new EsmShippingLookups.BundlePolicy(
                "662995", "1", BigDecimal.ZERO, null, true, "무료")));

        Map<String, String> saved = policies.forListing(cell, Map.of(
                EsmShippingKeys.PLACE_NO, "176042", EsmShippingKeys.BUNDLE_POLICY_NO, "662995"));

        assertThat(saved).containsEntry(EsmShippingKeys.BUNDLE_FEE_TYPE, "1");
    }

    @Test
    void returnFee_mustBeAWholeNumber() {
        assertThatThrownBy(() -> policies.forChannel(account, Map.of(EsmShippingKeys.RETURN_FEE, "-100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(EsmShippingPolicies.RETURN_FEE_INVALID);
    }

    @Test
    void listing_emptyOverride_isNull() {
        ProductListing cell = ProductListing.builder().id(1L).platform(Platform.ESM).build();

        assertThat(policies.forListing(cell, Map.of())).isNull();
    }
}
