package com.pms.service.listing.shipping;

import com.pms.domain.EsmShippingConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.repository.EsmShippingConfigRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.CustomerShippingFee;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an ESM cell's shipping settings (FEATURE_2610_26 / D36 · D66):
 *
 * <pre>
 *   place + bundle-fee group = the listing override's group when it set both, else the channel's (one unit)
 *   every other key          = listing override ?? channel (EsmShippingConfig)
 * </pre>
 *
 * <p>There is no master layer (D36). A blank value means "inherit".</p>
 *
 * <p>⚠️ Touches LAZY {@code cell.seller} → call inside a transaction.</p>
 */
@Component
@RequiredArgsConstructor
public class EsmShippingResolver {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final EsmShippingConfigRepository esmShippingConfigRepository;

    /** Fully resolved settings of this cell (non-blank values only). */
    public Map<String, String> resolve(ProductListing cell) {
        return build(cell.getShippingOverride(), baseSettings(cell));
    }

    /** The channel layer alone (this cell's own override excluded) — the listing modal's placeholder. */
    public Map<String, String> resolveInherited(ProductListing cell) {
        return build(null, baseSettings(cell));
    }

    /** D36: the labels of the input keys that are still empty, in screen order (empty list = ready). */
    public static List<String> missingLabels(Map<String, String> resolved) {
        List<String> missing = new ArrayList<>();
        for (String key : EsmShippingKeys.INPUT_KEYS) {
            String value = resolved.get(key);
            if (value == null || value.isBlank()) {
                missing.add(EsmShippingKeys.LABELS.get(key));
            }
        }
        return missing;
    }

    /**
     * D60: the buyer's prepaid shipping fee the 3.3% applies to — free (1) or cash on delivery only (prepay false) =
     * none; paid (2) = the fee; conditional (3) = the fee below the free threshold.
     */
    public static CustomerShippingFee customerFee(Map<String, String> resolved) {
        if (!"true".equals(resolved.get(EsmShippingKeys.BUNDLE_PREPAY))) {
            return CustomerShippingFee.NONE;
        }
        return switch (String.valueOf(resolved.get(EsmShippingKeys.BUNDLE_FEE_TYPE))) {
            case "2" -> new CustomerShippingFee(amount(resolved, EsmShippingKeys.BUNDLE_FEE), null);
            case "3" -> new CustomerShippingFee(amount(resolved, EsmShippingKeys.BUNDLE_FEE),
                    amount(resolved, EsmShippingKeys.BUNDLE_FREE_OVER));
            default -> CustomerShippingFee.NONE;
        };
    }

    static Map<String, String> build(Map<String, String> listing, Map<String, String> base) {
        Map<String, String> group = hasBundle(listing) ? listing : base;
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : EsmShippingKeys.ALL_KEYS) {
            String value = EsmShippingKeys.BUNDLE_GROUP.contains(key)
                    ? nonBlank(get(group, key))
                    : firstNonBlank(get(listing, key), get(base, key));
            if (value != null) {
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    /** D66: a layer owns the place + bundle-fee group only when it set both. */
    static boolean hasBundle(Map<String, String> settings) {
        return nonBlank(get(settings, EsmShippingKeys.PLACE_NO)) != null
                && nonBlank(get(settings, EsmShippingKeys.BUNDLE_POLICY_NO)) != null;
    }

    private Map<String, String> baseSettings(ProductListing cell) {
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), Platform.ESM)
                .orElse(null);
        if (account == null) {
            return null;
        }
        return esmShippingConfigRepository.findByMarketplaceAccountId(account.getId())
                .map(EsmShippingConfig::getSettings)
                .orElse(null);
    }

    private static BigDecimal amount(Map<String, String> resolved, String key) {
        String value = resolved.get(key);
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
    }

    private static String get(Map<String, String> map, String key) {
        return map == null ? null : map.get(key);
    }

    private static String firstNonBlank(String first, String second) {
        String value = nonBlank(first);
        return value != null ? value : nonBlank(second);
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
