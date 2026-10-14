package com.pms.service.listing.shipping;

import com.pms.domain.ElevenstShippingConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.ElevenstShippingConfigRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.CustomerShippingFee;
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

/** FEATURE_2610_13 / D85 · D91 · D92 · D104 · D106 · D111. */
@ExtendWith(MockitoExtension.class)
class ElevenstShippingResolverTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private ElevenstShippingConfigRepository elevenstShippingConfigRepository;
    @InjectMocks private ElevenstShippingResolver resolver;

    @Test
    void resolve_listingBeatsMasterBeatsChannel_addressSkipsMaster() {
        MarketplaceAccount account = MarketplaceAccount.builder().id(3L).platform(Platform.ELEVENST).build();
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(7L, Platform.ELEVENST))
                .willReturn(Optional.of(account));
        given(elevenstShippingConfigRepository.findByMarketplaceAccountId(3L))
                .willReturn(Optional.of(ElevenstShippingConfig.builder().settings(Map.of(
                        ElevenstShippingKeys.DLV_CST1, "3000",
                        ElevenstShippingKeys.JEJU_DLV_CST, "5000",
                        ElevenstShippingKeys.ADDR_SEQ_OUT, "11")).build()));
        MasterProduct master = MasterProduct.builder().id(1L).shippingOverride(Map.of(
                ElevenstShippingKeys.DLV_CST1, "2500",
                ElevenstShippingKeys.ADDR_SEQ_OUT, "99")).build();
        ProductListing cell = ProductListing.builder().id(100L).platform(Platform.ELEVENST)
                .seller(Seller.builder().id(7L).build()).masterProduct(master)
                .shippingOverride(Map.of(ElevenstShippingKeys.JEJU_DLV_CST, "6000")).build();

        Map<String, String> resolved = resolver.resolve(cell);

        assertThat(resolved)
                .containsEntry(ElevenstShippingKeys.DLV_CST1, "2500")       // master beats channel
                .containsEntry(ElevenstShippingKeys.JEJU_DLV_CST, "6000")   // listing beats channel
                .containsEntry(ElevenstShippingKeys.ADDR_SEQ_OUT, "11");     // master address ignored
    }

    @Test
    void missingLabels_fixedFeeWithoutAmount_listsFeeAndAddresses() {
        assertThat(ElevenstShippingResolver.missingLabels(Map.of(
                ElevenstShippingKeys.DLV_CN_AREA_CD, "01",
                ElevenstShippingKeys.DLV_WY_CD, "01",
                ElevenstShippingKeys.DLV_ETPRS_CD, "00034",
                ElevenstShippingKeys.DLV_CST_INST_BASI_CD, "02",
                ElevenstShippingKeys.DLV_CST_PAY_TYP_CD, "03",
                ElevenstShippingKeys.BNDL_DLV_CN_YN, "Y",
                ElevenstShippingKeys.JEJU_DLV_CST, "3000",
                ElevenstShippingKeys.ISLAND_DLV_CST, "5000",
                ElevenstShippingKeys.RTNGD_DLV_CST, "3000",
                ElevenstShippingKeys.EXCH_DLV_CST, "6000")))
                .containsExactly("출고지", "반품/교환지", "배송비");
    }

    @Test
    void customerFee_conditionalFree_chargedBelowThresholdOnly() {
        CustomerShippingFee fee = ElevenstShippingResolver.customerFee(Map.of(
                ElevenstShippingKeys.DLV_CST_INST_BASI_CD, "03",
                ElevenstShippingKeys.DLV_CST_PAY_TYP_CD, "01",
                ElevenstShippingKeys.DLV_CST1, "3000",
                ElevenstShippingKeys.PRD_FR_DLV_BASI_AMT, "30000"));

        assertThat(fee.costAt(new BigDecimal("29990"))).isEqualByComparingTo("99");
        assertThat(fee.costAt(new BigDecimal("30000"))).isEqualByComparingTo("0");
    }

    @Test
    void customerFee_cashOnDelivery_isNone_quantityTiers_useFirstTier() {
        assertThat(ElevenstShippingResolver.customerFee(Map.of(
                ElevenstShippingKeys.DLV_CST_INST_BASI_CD, "02",
                ElevenstShippingKeys.DLV_CST_PAY_TYP_CD, "02",
                ElevenstShippingKeys.DLV_CST1, "3000"))).isEqualTo(CustomerShippingFee.NONE);
        assertThat(ElevenstShippingResolver.customerFee(Map.of(
                ElevenstShippingKeys.DLV_CST_INST_BASI_CD, "04",
                ElevenstShippingKeys.DLV_CST_PAY_TYP_CD, "03",
                ElevenstShippingKeys.DLV_CST3, "5000^1000^0")).costAt(new BigDecimal("10000")))
                .isEqualByComparingTo("165");
    }
}
