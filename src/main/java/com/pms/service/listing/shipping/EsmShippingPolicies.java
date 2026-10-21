package com.pms.service.listing.shipping;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.esm.EsmShippingLookups;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The save-time rules of the ESM shipping settings (FEATURE_2610_26 / D36 · D60 · D66), shared by the channel save
 * and the listing override save:
 *
 * <ol>
 *   <li>Only the six input keys are taken from the client; the four policy keys it sends are dropped (D66).</li>
 *   <li>{@code esm.returnFee} = a whole number ≥ 0 (D36).</li>
 *   <li>A listing override sets the shipping place and the bundle-fee policy together or neither (D66).</li>
 *   <li>A bundle-fee policy is looked up in its shipping place's list on ESM (post 18, with the channel's key): not
 *       there (or no place) → 400; there → its four facts are written as the policy keys (D60 · D66). ESM is called
 *       only when a policy is set — a server that does not call ESM answers 503 then (D66).</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class EsmShippingPolicies {

    static final String NOT_OF_PLACE = "고른 묶음배송비는 이 출하지의 정책이 아닙니다";
    static final String PAIR_REQUIRED = "출하지와 묶음배송비는 함께 고르세요";
    static final String RETURN_FEE_INVALID = "반품 배송비는 0 이상의 정수여야 합니다.";

    private final EsmShippingLookups lookups;
    private final MarketplaceAccountRepository marketplaceAccountRepository;

    /** The channel layer to store (never null — an empty map when nothing is set). */
    public Map<String, String> forChannel(MarketplaceAccount account, Map<String, String> raw) {
        Map<String, String> settings = inputs(raw);
        withPolicy(account, settings);
        return settings;
    }

    /** The listing override to store ({@code null} when nothing is left = inherit everything). */
    public Map<String, String> forListing(ProductListing cell, Map<String, String> raw) {
        Map<String, String> settings = inputs(raw);
        boolean place = !isBlank(settings.get(EsmShippingKeys.PLACE_NO));
        boolean bundle = !isBlank(settings.get(EsmShippingKeys.BUNDLE_POLICY_NO));
        if (place != bundle) {
            throw new IllegalArgumentException(PAIR_REQUIRED);
        }
        if (bundle) {
            MarketplaceAccount account = marketplaceAccountRepository
                    .findBySeller_IdAndPlatform(cell.getSeller().getId(), Platform.ESM)
                    .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", cell.getSeller().getId()));
            withPolicy(account, settings);
        }
        return settings.isEmpty() ? null : settings;
    }

    private static Map<String, String> inputs(Map<String, String> raw) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (raw != null) {
            for (String key : EsmShippingKeys.INPUT_KEYS) {
                if (raw.containsKey(key)) {
                    settings.put(key, raw.get(key));
                }
            }
        }
        String returnFee = settings.get(EsmShippingKeys.RETURN_FEE);
        if (!isBlank(returnFee) && !returnFee.trim().matches("\\d+")) {
            throw new IllegalArgumentException(RETURN_FEE_INVALID);
        }
        return settings;
    }

    private void withPolicy(MarketplaceAccount account, Map<String, String> settings) {
        String policyNo = settings.get(EsmShippingKeys.BUNDLE_POLICY_NO);
        if (isBlank(policyNo)) {
            return;
        }
        String placeNo = settings.get(EsmShippingKeys.PLACE_NO);
        if (isBlank(placeNo)) {
            throw new IllegalArgumentException(NOT_OF_PLACE);
        }
        EsmShippingLookups.BundlePolicy policy = lookups.bundlePolicies(account, placeNo.trim()).stream()
                .filter(p -> p.policyNo().equals(policyNo.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(NOT_OF_PLACE));
        settings.put(EsmShippingKeys.BUNDLE_FEE_TYPE, policy.feeType());
        settings.put(EsmShippingKeys.BUNDLE_FEE, policy.fee().stripTrailingZeros().toPlainString());
        if (policy.freeOver() != null) {
            settings.put(EsmShippingKeys.BUNDLE_FREE_OVER, policy.freeOver().stripTrailingZeros().toPlainString());
        }
        settings.put(EsmShippingKeys.BUNDLE_PREPAY, String.valueOf(policy.prepay()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
