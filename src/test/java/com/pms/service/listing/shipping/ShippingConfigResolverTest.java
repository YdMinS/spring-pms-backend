package com.pms.service.listing.shipping;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceShippingConfig;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MarketplaceShippingConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link ShippingConfigResolver} (FEATURE_2608_06 / 75): field-wise 3-level resolution
 * {@code channel ?? master ?? account default}, with outbound place / return center resolved
 * {@code channel ?? account} only (master step skipped). Pure combination → Mockito unit test.
 */
@ExtendWith(MockitoExtension.class)
class ShippingConfigResolverTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private MarketplaceShippingConfigRepository shippingConfigRepository;
    @InjectMocks private ShippingConfigResolver resolver;

    private static final Long SELLER_ID = 7L;
    private static final Long ACCOUNT_ID = 3L;

    private ProductListing cell(Map<String, String> listingOverride, Map<String, String> masterOverride) {
        MasterProduct master = masterOverride == null ? null
                : MasterProduct.builder().id(1L).shippingOverride(masterOverride).build();
        return ProductListing.builder()
                .id(100L).platform("COUPANG")
                .seller(Seller.builder().id(SELLER_ID).build())
                .masterProduct(master)
                .shippingOverride(listingOverride)
                .build();
    }

    /** Stub the (seller, platform) account + its stored base config. */
    private void withBaseConfig(MarketplaceShippingConfig base) {
        MarketplaceAccount account = MarketplaceAccount.builder().id(ACCOUNT_ID).build();
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, "COUPANG"))
                .willReturn(Optional.of(account));
        given(shippingConfigRepository.findByMarketplaceAccountId(ACCOUNT_ID))
                .willReturn(Optional.ofNullable(base));
    }

    private MarketplaceShippingConfig baseConfig() {
        return MarketplaceShippingConfig.builder()
                .outboundShippingPlaceCode("OUT-1")
                .returnCenterCode("RC-1").returnChargeName("반품담당").returnContactNumber("021234567")
                .returnZipCode("06000").returnAddress("서울시").returnAddressDetail("1층")
                .returnCharge(new BigDecimal("2500")).deliveryChargeOnReturn(new BigDecimal("2500"))
                .deliveryMethod("SEQUENCIAL").deliveryCompanyCode("CJGLS").deliveryChargeType("FREE")
                .deliveryCharge(new BigDecimal("2500")).remoteAreaDeliverable("N")
                .unionDeliveryType("NOT_UNION_DELIVERY").extraInfoMessage("계정기본")
                .build();
    }

    @Test
    void accountOnly_returnsBaseValues() {
        withBaseConfig(baseConfig());

        ResolvedShippingConfig r = resolver.resolve(cell(null, null));

        assertThat(r.deliveryMethod()).isEqualTo("SEQUENCIAL");
        assertThat(r.deliveryCompanyCode()).isEqualTo("CJGLS");
        assertThat(r.deliveryCharge()).isEqualByComparingTo("2500");
        assertThat(r.outboundShippingPlaceCode()).isEqualTo("OUT-1");
        assertThat(r.extraInfoMessage()).isEqualTo("계정기본");
    }

    // GWT (prompt §Step 3): config{method=SEQUENCIAL, company=CJGLS, charge=2500} + master{method=MAKE_ORDER}
    //   + listing{charge="3000"} → method=MAKE_ORDER (master), company=CJGLS (account), charge=3000 (listing).
    @Test
    void fieldWiseMixedInheritance() {
        withBaseConfig(baseConfig());

        ResolvedShippingConfig r = resolver.resolve(cell(
                Map.of(ShippingOverrideKeys.DELIVERY_CHARGE, "3000"),
                Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "MAKE_ORDER")));

        assertThat(r.deliveryMethod()).isEqualTo("MAKE_ORDER");        // master
        assertThat(r.deliveryCompanyCode()).isEqualTo("CJGLS");        // account default
        assertThat(r.deliveryCharge()).isEqualByComparingTo("3000");   // listing (parsed from string)
    }

    @Test
    void listingOverride_winsOverMasterAndAccount() {
        withBaseConfig(baseConfig());

        ResolvedShippingConfig r = resolver.resolve(cell(
                Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "LISTING_M"),
                Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "MASTER_M")));

        assertThat(r.deliveryMethod()).isEqualTo("LISTING_M");
    }

    @Test
    void blankOverride_inherits() {
        withBaseConfig(baseConfig());

        ResolvedShippingConfig r = resolver.resolve(cell(
                Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "   "),   // blank = inherit
                null));

        assertThat(r.deliveryMethod()).isEqualTo("SEQUENCIAL");   // account default
    }

    // Carrier code is 3-level (master allowed). master overrides, no listing → master wins over account.
    @Test
    void carrierCode_isThreeLevel() {
        withBaseConfig(baseConfig());

        ResolvedShippingConfig r = resolver.resolve(cell(
                null,
                Map.of(ShippingOverrideKeys.DELIVERY_COMPANY_CODE, "HANJIN")));

        assertThat(r.deliveryCompanyCode()).isEqualTo("HANJIN");
    }

    // Outbound / return center = channel ?? account only: a master override for those keys is IGNORED.
    @Test
    void outboundAndReturn_ignoreMasterOverride() {
        withBaseConfig(baseConfig());

        ResolvedShippingConfig r = resolver.resolve(cell(
                null,
                Map.of(ShippingOverrideKeys.OUTBOUND_SHIPPING_PLACE_CODE, "MASTER-OUT",
                        ShippingOverrideKeys.RETURN_CENTER_CODE, "MASTER-RC")));

        assertThat(r.outboundShippingPlaceCode()).isEqualTo("OUT-1");   // account (master ignored)
        assertThat(r.returnCenterCode()).isEqualTo("RC-1");
    }

    // A listing override for a place key DOES win (channel level).
    @Test
    void outbound_listingOverrideWins() {
        withBaseConfig(baseConfig());

        ResolvedShippingConfig r = resolver.resolve(cell(
                Map.of(ShippingOverrideKeys.OUTBOUND_SHIPPING_PLACE_CODE, "LISTING-OUT"),
                null));

        assertThat(r.outboundShippingPlaceCode()).isEqualTo("LISTING-OUT");
    }

    // Account absent (no config): only overrides apply; every other field is null (all-null base).
    @Test
    void accountAbsent_onlyOverridesApply() {
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(SELLER_ID, "COUPANG"))
                .willReturn(Optional.empty());

        ResolvedShippingConfig r = resolver.resolve(cell(
                Map.of(ShippingOverrideKeys.DELIVERY_METHOD, "LISTING_M"),
                null));

        assertThat(r.deliveryMethod()).isEqualTo("LISTING_M");
        assertThat(r.deliveryCompanyCode()).isNull();
        assertThat(r.outboundShippingPlaceCode()).isNull();
    }
}
