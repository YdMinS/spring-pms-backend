package com.pms.service.listing.shipping;

import com.pms.domain.LotteonShippingConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.repository.LotteonShippingConfigRepository;
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
 * Resolves a LotteON cell's shipping settings (FEATURE_2610_24 / D42 · D65):
 *
 * <pre>
 *   outbound place · return place = listing override ?? channel (LotteonShippingConfig)
 *   shipping fee policy + its facts = the listing override's group when it picked a policy, else the channel's
 * </pre>
 *
 * <p>There is no master layer (D42). A blank value means "inherit".</p>
 *
 * <p>⚠️ Touches LAZY {@code cell.seller} → call inside a transaction.</p>
 */
@Component
@RequiredArgsConstructor
public class LotteonShippingResolver {

    /** D66: 166 {@code dvCstDvsCd} — A paid · C conditional (B free has no fee). */
    static final String PAID = "A";
    static final String CONDITIONAL = "C";

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final LotteonShippingConfigRepository lotteonShippingConfigRepository;

    /** Fully resolved settings of this cell (non-blank values only). */
    public Map<String, String> resolve(ProductListing cell) {
        return build(cell.getShippingOverride(), baseSettings(cell));
    }

    /** The channel layer alone (this cell's own override excluded) — the listing modal's placeholder. */
    public Map<String, String> resolveInherited(ProductListing cell) {
        return build(null, baseSettings(cell));
    }

    /** D42: the labels of the input keys that are still empty, in screen order (empty list = ready). */
    public static List<String> missingLabels(Map<String, String> resolved) {
        List<String> missing = new ArrayList<>();
        for (String key : LotteonShippingKeys.INPUT_KEYS) {
            String value = resolved.get(key);
            if (value == null || value.isBlank()) {
                missing.add(LotteonShippingKeys.LABELS.get(key));
            }
        }
        return missing;
    }

    /**
     * D66: the buyer's shipping fee the LotteON commission applies to, at {@code rate} (the cell's stored commission ×
     * (1 + fee VAT rate)) — paid (A) = the fee; conditional (C) = the fee below the free threshold; free (B) or no
     * policy facts = none.
     */
    public static CustomerShippingFee customerFee(Map<String, String> resolved, BigDecimal rate) {
        String kind = resolved.get(LotteonShippingKeys.DV_CST_DVS_CD);
        if (PAID.equals(kind)) {
            return new CustomerShippingFee(amount(resolved, LotteonShippingKeys.DV_CST), null, rate);
        }
        if (CONDITIONAL.equals(kind)) {
            return new CustomerShippingFee(amount(resolved, LotteonShippingKeys.DV_CST),
                    nullableAmount(resolved, LotteonShippingKeys.CNDL_FREE_STD_AMT), rate);
        }
        return CustomerShippingFee.NONE;
    }

    static Map<String, String> build(Map<String, String> listing, Map<String, String> base) {
        Map<String, String> group = hasPolicy(listing) ? listing : base;
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : LotteonShippingKeys.ALL_KEYS) {
            String value = LotteonShippingKeys.FEE_GROUP.contains(key)
                    ? nonBlank(get(group, key))
                    : firstNonBlank(get(listing, key), get(base, key));
            if (value != null) {
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    /** D65: a layer owns the policy group only when it picked a policy. */
    static boolean hasPolicy(Map<String, String> settings) {
        return nonBlank(get(settings, LotteonShippingKeys.DV_CST_POL_NO)) != null;
    }

    private Map<String, String> baseSettings(ProductListing cell) {
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), Platform.LOTTEON)
                .orElse(null);
        if (account == null) {
            return null;
        }
        return lotteonShippingConfigRepository.findByMarketplaceAccountId(account.getId())
                .map(LotteonShippingConfig::getSettings)
                .orElse(null);
    }

    private static BigDecimal amount(Map<String, String> resolved, String key) {
        BigDecimal value = nullableAmount(resolved, key);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal nullableAmount(Map<String, String> resolved, String key) {
        String value = resolved.get(key);
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
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
