package com.pms.service.listing.shipping;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The save-time rules of the LotteON shipping settings (FEATURE_2610_24 / D42 · D65), shared by the channel save and
 * the listing override save:
 *
 * <ol>
 *   <li>Only the three input keys are taken from the client; the policy keys it sends are dropped (D65).</li>
 *   <li>A shipping fee policy number is looked up in the channel's policy list on LotteON (166): not there → 400; there
 *       → its kind, fee and free threshold are written as the policy keys. LotteON is called only when a policy is
 *       set — a server that does not call LotteON answers 503 then (D65 · D8).</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class LotteonShippingPolicies {

    static final String POLICY_NOT_FOUND = "고른 배송비 정책이 롯데ON 에 없습니다";

    private final LotteonShippingLookups lookups;
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
        if (!isBlank(settings.get(LotteonShippingKeys.DV_CST_POL_NO))) {
            MarketplaceAccount account = marketplaceAccountRepository
                    .findBySeller_IdAndPlatform(cell.getSeller().getId(), Platform.LOTTEON)
                    .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", cell.getSeller().getId()));
            withPolicy(account, settings);
        }
        return settings.isEmpty() ? null : settings;
    }

    private static Map<String, String> inputs(Map<String, String> raw) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (raw != null) {
            for (String key : LotteonShippingKeys.INPUT_KEYS) {
                if (raw.containsKey(key)) {
                    settings.put(key, raw.get(key));
                }
            }
        }
        return settings;
    }

    private void withPolicy(MarketplaceAccount account, Map<String, String> settings) {
        String policyNo = settings.get(LotteonShippingKeys.DV_CST_POL_NO);
        if (isBlank(policyNo)) {
            return;
        }
        LotteonShippingLookups.FeePolicy policy = lookups.feePolicies(account).stream()
                .filter(p -> p.policyNo().equals(policyNo.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(POLICY_NOT_FOUND));
        settings.put(LotteonShippingKeys.DV_CST_DVS_CD, policy.dvsCd());
        if (policy.fee() != null) {
            settings.put(LotteonShippingKeys.DV_CST, plain(policy.fee()));
        }
        if (policy.freeOver() != null) {
            settings.put(LotteonShippingKeys.CNDL_FREE_STD_AMT, plain(policy.freeOver()));
        }
    }

    /** D65: the amount without trailing zeros (3000.00 → 「3000」). */
    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
