package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.listing.OptionCheckSuffix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * "옵션확인" suffix resolution (69): field-wise chain channel(account) ?? master ?? seller ?? system default.
 * enabled and text resolve independently; all-null → system default OFF (enabled=false = no suffix).
 */
@ExtendWith(MockitoExtension.class)
class OptionCheckSuffixResolverTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @InjectMocks private OptionCheckSuffixResolver resolver;

    private Seller seller(Boolean enabled, String suffix) {
        return Seller.builder().id(1L).sellerName("판매자").businessRegistration("111")
                .optionCheckSuffixEnabled(enabled).optionCheckSuffix(suffix).build();
    }

    private MasterProduct master(Boolean enabled, String suffix) {
        return MasterProduct.builder().id(2L).name("마스터").active(true)
                .optionCheckSuffixEnabled(enabled).optionCheckSuffix(suffix).build();
    }

    private MarketplaceAccount account(Boolean enabled, String suffix) {
        return MarketplaceAccount.builder().id(3L).platform("COUPANG").vendorId("V").accessKey("ak")
                .secretKey("sk").isActive(true)
                .optionCheckSuffixEnabled(enabled).optionCheckSuffix(suffix).build();
    }

    @Test
    void channelOverride_winsOverMasterAndSeller() {
        OptionCheckSuffix result = resolver.resolve(
                account(false, "채널문구"), master(true, "마스터문구"), seller(true, "판매자문구"));

        assertThat(result.enabled()).isFalse();
        assertThat(result.text()).isEqualTo("채널문구");
    }

    @Test
    void masterOverride_winsOverSeller_whenChannelNull() {
        OptionCheckSuffix result = resolver.resolve(
                account(null, null), master(false, "마스터문구"), seller(true, "판매자문구"));

        assertThat(result.enabled()).isFalse();          // master
        assertThat(result.text()).isEqualTo("마스터문구");  // master
    }

    @Test
    void enabledAndText_resolveIndependently() {
        // enabled comes from master (false), text comes from seller (channel+master text null/blank → seller).
        OptionCheckSuffix result = resolver.resolve(
                account(null, null), master(false, "   "), seller(null, "판매자문구"));

        assertThat(result.enabled()).isFalse();           // master enabled
        assertThat(result.text()).isEqualTo("판매자문구");  // seller text (master text blank = inherit)
    }

    @Test
    void allNull_defaultsToDisabled_noSuffix() {
        // System default OFF (user decision 2026-08-27): nothing configured → no suffix.
        OptionCheckSuffix result = resolver.resolve(null, null, null);

        assertThat(result.enabled()).isFalse();
    }

    @Test
    void resolveForMaster_skipsSeller_masterOrSystemOnly() {
        // Master override applies; seller is intentionally not consulted at the master level.
        OptionCheckSuffix result = resolver.resolveForMaster(master(false, "마스터문구"));

        assertThat(result.enabled()).isFalse();
        assertThat(result.text()).isEqualTo("마스터문구");
    }

    @Test
    void resolveCell_loadsAccountBySellerAndPlatform_thenResolves() {
        Seller seller = seller(true, "판매자문구");
        MasterProduct master = master(null, null);
        MarketplaceAccount account = account(false, "채널문구");
        ProductListing cell = ProductListing.builder()
                .id(100L).platform("COUPANG").name("셀").seller(seller).masterProduct(master).build();
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(1L, "COUPANG"))
                .willReturn(Optional.of(account));

        OptionCheckSuffix result = resolver.resolve(cell);

        // channel override wins for both fields.
        assertThat(result.enabled()).isFalse();
        assertThat(result.text()).isEqualTo("채널문구");
    }

    @Test
    void resolveCell_noAccount_fallsThroughToMasterThenSeller() {
        Seller seller = seller(true, "판매자문구");
        MasterProduct master = master(false, null);   // enabled from master, text from seller
        ProductListing cell = ProductListing.builder()
                .id(100L).platform("COUPANG").name("셀").seller(seller).masterProduct(master).build();
        given(marketplaceAccountRepository.findBySeller_IdAndPlatform(1L, "COUPANG"))
                .willReturn(Optional.empty());

        OptionCheckSuffix result = resolver.resolve(cell);

        assertThat(result.enabled()).isFalse();           // master
        assertThat(result.text()).isEqualTo("판매자문구");  // seller (master text null)
    }
}
