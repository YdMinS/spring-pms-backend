package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceShippingConfig;
import com.pms.dto.request.ShippingConfigRequest;
import com.pms.dto.response.ShippingConfigResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MarketplaceShippingConfigRepository;
import com.pms.service.listing.shipping.OutboundPlace;
import com.pms.service.listing.shipping.ReturnCenter;
import com.pms.service.listing.shipping.ShippingPlaceProvider;
import com.pms.service.listing.shipping.ShippingPlaceProviderResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * {@link ShippingConfigService} implementation (FEATURE_2608_06 / 72).
 *
 * <p>Lookup-first: when the platform has a {@link ShippingPlaceProvider} the places/centers are fetched from the
 * platform; otherwise an empty list is returned and the front shows manual entry (an unsupported platform is a
 * 200 empty array, not an error). The config upsert stores lookup-picked and manually-entered values through the
 * same path.</p>
 *
 * <p>⚠️ Account load = the same convention as {@code MarketplaceAccountServiceImpl} ({@code findById} → 404).
 * @TenantId PK-find is unfiltered here, which is the <b>same posture</b> as the existing account CRUD (the
 * endpoints are ADMIN-gated) — this service deliberately adds no stricter guard (cross-tenant hardening is a
 * separate account-service-level follow-up, to stay consistent with the rest of account CRUD).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShippingConfigServiceImpl implements ShippingConfigService {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final MarketplaceShippingConfigRepository shippingConfigRepository;
    private final ShippingPlaceProviderResolver providerResolver;

    @Override
    public List<OutboundPlace> listOutbound(Long accountId) {
        MarketplaceAccount account = findAccount(accountId);
        return providerResolver.resolve(account.getPlatform())
                .map(provider -> provider.fetchOutboundPlaces(account))
                .orElseGet(List::of);   // no platform lookup → empty = manual entry
    }

    @Override
    public List<ReturnCenter> listReturn(Long accountId) {
        MarketplaceAccount account = findAccount(accountId);
        return providerResolver.resolve(account.getPlatform())
                .map(provider -> provider.fetchReturnCenters(account))
                .orElseGet(List::of);
    }

    @Override
    public ShippingConfigResponse getConfig(Long accountId) {
        findAccount(accountId);   // 404 when the account does not exist
        return shippingConfigRepository.findByMarketplaceAccountId(accountId)
                .map(ShippingConfigResponse::from)
                .orElseGet(() -> ShippingConfigResponse.empty(accountId));
    }

    @Override
    @Transactional
    public ShippingConfigResponse upsertConfig(Long accountId, ShippingConfigRequest req) {
        MarketplaceAccount account = findAccount(accountId);
        Optional<MarketplaceShippingConfig> existing =
                shippingConfigRepository.findByMarketplaceAccountId(accountId);

        // toBuilder from the existing row keeps its id (an update, not a fresh insert); a new one starts fresh.
        MarketplaceShippingConfig.MarketplaceShippingConfigBuilder builder = existing
                .map(MarketplaceShippingConfig::toBuilder)
                .orElseGet(() -> MarketplaceShippingConfig.builder().marketplaceAccount(account));

        MarketplaceShippingConfig saved = shippingConfigRepository.save(builder
                .outboundShippingPlaceCode(req.getOutboundShippingPlaceCode())
                .returnCenterCode(req.getReturnCenterCode())
                .returnChargeName(req.getReturnChargeName())
                .returnContactNumber(req.getReturnContactNumber())
                .returnZipCode(req.getReturnZipCode())
                .returnAddress(req.getReturnAddress())
                .returnAddressDetail(req.getReturnAddressDetail())
                .returnCharge(req.getReturnCharge())
                .deliveryChargeOnReturn(req.getDeliveryChargeOnReturn())
                .deliveryMethod(req.getDeliveryMethod())
                .deliveryCompanyCode(req.getDeliveryCompanyCode())
                .deliveryChargeType(req.getDeliveryChargeType())
                .deliveryCharge(req.getDeliveryCharge())
                .freeShipOverAmount(req.getFreeShipOverAmount())
                .remoteAreaDeliverable(req.getRemoteAreaDeliverable())
                .unionDeliveryType(req.getUnionDeliveryType())
                .build());

        return ShippingConfigResponse.from(saved);
    }

    private MarketplaceAccount findAccount(Long accountId) {
        return marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
    }
}
