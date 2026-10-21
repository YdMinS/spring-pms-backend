package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.CoupangShippingConfig;
import com.pms.domain.ElevenstShippingConfig;
import com.pms.domain.EsmShippingConfig;
import com.pms.domain.Platform;
import com.pms.dto.request.ShippingConfigRequest;
import com.pms.dto.response.ElevenstShippingConfigResponse;
import com.pms.dto.response.EsmChoiceResponse;
import com.pms.dto.response.EsmShippingConfigResponse;
import com.pms.dto.response.ShippingConfigResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.CoupangShippingConfigRepository;
import com.pms.repository.ElevenstShippingConfigRepository;
import com.pms.repository.EsmShippingConfigRepository;
import com.pms.service.esm.EsmShippingLookups;
import com.pms.service.listing.shipping.ElevenstShippingKeys;
import com.pms.service.listing.shipping.EsmShippingPolicies;
import com.pms.service.listing.shipping.OutboundPlace;
import com.pms.service.listing.shipping.ReturnCenter;
import com.pms.service.listing.shipping.ShippingPlaceProvider;
import com.pms.service.listing.shipping.ShippingPlaceProviderResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
    private final CoupangShippingConfigRepository shippingConfigRepository;
    private final ElevenstShippingConfigRepository elevenstShippingConfigRepository;
    private final ShippingPlaceProviderResolver providerResolver;
    private final EsmShippingConfigRepository esmShippingConfigRepository;
    private final EsmShippingPolicies esmShippingPolicies;
    private final EsmShippingLookups esmShippingLookups;

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
        Optional<CoupangShippingConfig> existing =
                shippingConfigRepository.findByMarketplaceAccountId(accountId);

        // toBuilder from the existing row keeps its id (an update, not a fresh insert); a new one starts fresh.
        CoupangShippingConfig.CoupangShippingConfigBuilder builder = existing
                .map(CoupangShippingConfig::toBuilder)
                .orElseGet(() -> CoupangShippingConfig.builder().marketplaceAccount(account));

        CoupangShippingConfig saved = shippingConfigRepository.save(builder
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
                .extraInfoMessage(req.getExtraInfoMessage())
                .build());

        return ShippingConfigResponse.from(saved);
    }

    @Override
    public ElevenstShippingConfigResponse getElevenstConfig(Long accountId) {
        requireElevenst(findAccount(accountId));
        Map<String, String> settings = elevenstShippingConfigRepository.findByMarketplaceAccountId(accountId)
                .map(ElevenstShippingConfig::getSettings)
                .orElse(null);
        return ElevenstShippingConfigResponse.builder()
                .marketplaceAccountId(accountId)
                .settings(settings != null ? settings : Map.of())
                .build();
    }

    @Override
    @Transactional
    public ElevenstShippingConfigResponse upsertElevenstConfig(Long accountId, Map<String, String> settings) {
        MarketplaceAccount account = requireElevenst(findAccount(accountId));
        Map<String, String> filtered = ElevenstShippingKeys.filter(settings, ElevenstShippingKeys.ALL_KEYS);
        ElevenstShippingKeys.validateTiers(filtered);
        // toBuilder from the existing row keeps its id (UNIQUE per account); a new one starts fresh.
        ElevenstShippingConfig.ElevenstShippingConfigBuilder builder = elevenstShippingConfigRepository
                .findByMarketplaceAccountId(accountId)
                .map(ElevenstShippingConfig::toBuilder)
                .orElseGet(() -> ElevenstShippingConfig.builder().marketplaceAccount(account));
        ElevenstShippingConfig saved = elevenstShippingConfigRepository.save(builder.settings(filtered).build());
        return ElevenstShippingConfigResponse.builder()
                .marketplaceAccountId(accountId)
                .settings(saved.getSettings() != null ? saved.getSettings() : Map.of())
                .build();
    }

    @Override
    public EsmShippingConfigResponse getEsmConfig(Long accountId) {
        requireEsm(findAccount(accountId));
        Map<String, String> settings = esmShippingConfigRepository.findByMarketplaceAccountId(accountId)
                .map(EsmShippingConfig::getSettings)
                .orElse(null);
        return EsmShippingConfigResponse.builder()
                .marketplaceAccountId(accountId)
                .settings(settings != null ? settings : Map.of())
                .build();
    }

    @Override
    @Transactional
    public EsmShippingConfigResponse upsertEsmConfig(Long accountId, Map<String, String> settings) {
        MarketplaceAccount account = requireEsm(findAccount(accountId));
        // D66: input keys only, the bundle-fee policy checked on ESM and its four facts written by us.
        Map<String, String> checked = esmShippingPolicies.forChannel(account, settings);
        // toBuilder from the existing row keeps its id (UNIQUE per account); a new one starts fresh.
        EsmShippingConfig.EsmShippingConfigBuilder builder = esmShippingConfigRepository
                .findByMarketplaceAccountId(accountId)
                .map(EsmShippingConfig::toBuilder)
                .orElseGet(() -> EsmShippingConfig.builder().marketplaceAccount(account));
        EsmShippingConfig saved = esmShippingConfigRepository.save(builder.settings(checked).build());
        return EsmShippingConfigResponse.builder()
                .marketplaceAccountId(accountId)
                .settings(saved.getSettings() != null ? saved.getSettings() : Map.of())
                .build();
    }

    @Override
    public List<EsmChoiceResponse> listEsmBundlePolicies(Long accountId, String placeNo) {
        MarketplaceAccount account = requireEsm(findAccount(accountId));
        return esmShippingLookups.bundlePolicies(account, placeNo).stream()
                .map(policy -> new EsmChoiceResponse(policy.policyNo(), policy.name()))
                .toList();
    }

    @Override
    public List<EsmChoiceResponse> listEsmDispatchPolicies(Long accountId) {
        MarketplaceAccount account = requireEsm(findAccount(accountId));
        return esmShippingLookups.dispatchPolicies(account).stream()
                .map(choice -> new EsmChoiceResponse(choice.code(), choice.name()))
                .toList();
    }

    @Override
    public List<EsmChoiceResponse> listEsmDeliveryCompanies(Long accountId) {
        MarketplaceAccount account = requireEsm(findAccount(accountId));
        return esmShippingLookups.deliveryCompanies(account).stream()
                .map(choice -> new EsmChoiceResponse(choice.code(), choice.name()))
                .toList();
    }

    private static MarketplaceAccount requireEsm(MarketplaceAccount account) {
        if (account.getPlatform() != Platform.ESM) {
            throw new IllegalArgumentException("G마켓 · 옥션 판매채널이 아닙니다.");
        }
        return account;
    }

    private static MarketplaceAccount requireElevenst(MarketplaceAccount account) {
        if (account.getPlatform() != Platform.ELEVENST) {
            throw new IllegalArgumentException("11번가 판매채널이 아닙니다.");
        }
        return account;
    }

    private MarketplaceAccount findAccount(Long accountId) {
        return marketplaceAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", accountId));
    }
}
