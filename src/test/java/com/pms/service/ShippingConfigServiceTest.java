package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceShippingConfig;
import com.pms.dto.request.ShippingConfigRequest;
import com.pms.dto.response.ShippingConfigResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MarketplaceShippingConfigRepository;
import com.pms.service.listing.shipping.OutboundPlace;
import com.pms.service.listing.shipping.ShippingPlaceProvider;
import com.pms.service.listing.shipping.ShippingPlaceProviderResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShippingConfigServiceImpl} (FEATURE_2608_06 / 72): unsupported-platform lookup → empty list, upsert
 * insert vs update (same id via toBuilder), and missing account → 404. Mockito unit level.
 */
@ExtendWith(MockitoExtension.class)
class ShippingConfigServiceTest {

    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private MarketplaceShippingConfigRepository shippingConfigRepository;
    @Mock private ShippingPlaceProviderResolver providerResolver;
    @InjectMocks private ShippingConfigServiceImpl service;

    private MarketplaceAccount account(Long id, String platform) {
        return MarketplaceAccount.builder().id(id).platform(platform)
                .vendorId("V1").accessKey("ak").secretKey("sk").isActive(true).build();
    }

    // ---- lookup: unsupported platform → empty list (manual entry) ----

    @Test
    void listOutbound_unsupportedPlatform_returnsEmptyList() {
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(account(1L, "NAVER")));
        given(providerResolver.resolve("NAVER")).willReturn(Optional.empty());

        assertThat(service.listOutbound(1L)).isEmpty();
    }

    @Test
    void listOutbound_supportedPlatform_delegatesToProvider() {
        MarketplaceAccount acct = account(1L, "COUPANG");
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acct));
        ShippingPlaceProvider provider = org.mockito.Mockito.mock(ShippingPlaceProvider.class);
        given(providerResolver.resolve("COUPANG")).willReturn(Optional.of(provider));
        given(provider.fetchOutboundPlaces(acct)).willReturn(java.util.List.of(new OutboundPlace("74010", "기본출고지")));

        assertThat(service.listOutbound(1L)).extracting(OutboundPlace::code).containsExactly("74010");
    }

    // ---- upsert new: no existing config → save a fresh entity ----

    @Test
    void upsertConfig_new_savesFreshEntity() {
        MarketplaceAccount acct = account(1L, "COUPANG");
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acct));
        given(shippingConfigRepository.findByMarketplaceAccountId(1L)).willReturn(Optional.empty());
        given(shippingConfigRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ShippingConfigRequest req = ShippingConfigRequest.builder()
                .outboundShippingPlaceCode("74010")
                .returnCenterCode("RC-1")
                .deliveryCharge(new BigDecimal("2500"))
                .remoteAreaDeliverable("Y")
                .build();

        ShippingConfigResponse response = service.upsertConfig(1L, req);

        ArgumentCaptor<MarketplaceShippingConfig> captor = ArgumentCaptor.forClass(MarketplaceShippingConfig.class);
        verify(shippingConfigRepository).save(captor.capture());
        MarketplaceShippingConfig saved = captor.getValue();
        assertThat(saved.getId()).isNull();                         // fresh insert
        assertThat(saved.getMarketplaceAccount()).isSameAs(acct);
        assertThat(saved.getOutboundShippingPlaceCode()).isEqualTo("74010");
        assertThat(saved.getRemoteAreaDeliverable()).isEqualTo("Y");
        assertThat(response.getReturnCenterCode()).isEqualTo("RC-1");
    }

    // ---- upsert update: existing config → toBuilder keeps the same id, reflects changed field ----

    @Test
    void upsertConfig_update_keepsSameIdAndReflectsChange() {
        MarketplaceAccount acct = account(1L, "COUPANG");
        MarketplaceShippingConfig existing = MarketplaceShippingConfig.builder()
                .id(99L).marketplaceAccount(acct)
                .outboundShippingPlaceCode("OLD").build();
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.of(acct));
        given(shippingConfigRepository.findByMarketplaceAccountId(1L)).willReturn(Optional.of(existing));
        given(shippingConfigRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.upsertConfig(1L, ShippingConfigRequest.builder().outboundShippingPlaceCode("NEW").build());

        ArgumentCaptor<MarketplaceShippingConfig> captor = ArgumentCaptor.forClass(MarketplaceShippingConfig.class);
        verify(shippingConfigRepository).save(captor.capture());
        MarketplaceShippingConfig saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(99L);                   // update, not a new insert
        assertThat(saved.getOutboundShippingPlaceCode()).isEqualTo("NEW");
    }

    // ---- account absent → 404 ----

    @Test
    void upsertConfig_missingAccount_throws404() {
        given(marketplaceAccountRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertConfig(1L, ShippingConfigRequest.builder().build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
