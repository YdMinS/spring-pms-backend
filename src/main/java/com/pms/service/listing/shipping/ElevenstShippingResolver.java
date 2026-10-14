package com.pms.service.listing.shipping;

import com.pms.domain.ElevenstShippingConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.repository.ElevenstShippingConfigRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.CustomerShippingFee;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves an 11st cell's shipping settings key by key (FEATURE_2610_13 / D85 · D104):
 *
 * <pre>
 *   value(key) = listing override ?? master override ?? channel default (ElevenstShippingConfig)
 * </pre>
 *
 * <p>Address numbers ({@link ElevenstShippingKeys#PLACE_KEYS}) skip the master step (account-specific). A blank
 * value means "inherit". Same shape as {@link CoupangShippingConfigResolver}; the 11st values never mix with the
 * Coupang keys (the {@code elevenst.} prefix keeps them apart in the master's one map).</p>
 *
 * <p>⚠️ Touches LAZY {@code cell.masterProduct} / {@code cell.seller} → call inside a transaction.</p>
 */
@Component
@RequiredArgsConstructor
public class ElevenstShippingResolver {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ElevenstShippingConfigRepository elevenstShippingConfigRepository;

    /** Fully resolved settings of this cell (non-blank values only). */
    public Map<String, String> resolve(ProductListing cell) {
        return build(cell.getShippingOverride(), masterMap(cell), baseSettings(cell));
    }

    /** D104 placeholder baseline: master ?? channel default, this cell's own override excluded. */
    public Map<String, String> resolveInherited(ProductListing cell) {
        return build(null, masterMap(cell), baseSettings(cell));
    }

    /**
     * D91 register guard: the labels of required fields that are still empty (empty list = ready). Mirrors the
     * Coupang {@code ShippingReadiness} message shape; the fee-type-specific fields are required only for that
     * type, the courier only for parcel delivery (01).
     */
    public static List<String> missingLabels(Map<String, String> resolved) {
        Set<String> required = new LinkedHashSet<>(List.of(
                ElevenstShippingKeys.DLV_CN_AREA_CD, ElevenstShippingKeys.DLV_WY_CD,
                ElevenstShippingKeys.DLV_CST_INST_BASI_CD, ElevenstShippingKeys.DLV_CST_PAY_TYP_CD,
                ElevenstShippingKeys.BNDL_DLV_CN_YN, ElevenstShippingKeys.JEJU_DLV_CST,
                ElevenstShippingKeys.ISLAND_DLV_CST, ElevenstShippingKeys.RTNGD_DLV_CST,
                ElevenstShippingKeys.EXCH_DLV_CST, ElevenstShippingKeys.ADDR_SEQ_OUT,
                ElevenstShippingKeys.ADDR_SEQ_IN));
        if ("01".equals(resolved.get(ElevenstShippingKeys.DLV_WY_CD))) {
            required.add(ElevenstShippingKeys.DLV_ETPRS_CD);
        }
        switch (String.valueOf(resolved.get(ElevenstShippingKeys.DLV_CST_INST_BASI_CD))) {
            case "02" -> required.add(ElevenstShippingKeys.DLV_CST1);
            case "03" -> {
                required.add(ElevenstShippingKeys.DLV_CST1);
                required.add(ElevenstShippingKeys.PRD_FR_DLV_BASI_AMT);
            }
            case "04" -> {
                required.add(ElevenstShippingKeys.DLV_CNT1);
                required.add(ElevenstShippingKeys.DLV_CST3);
            }
            case "05" -> required.add(ElevenstShippingKeys.DLV_CST4);
            default -> {
                // 01 free · 07/08 conditional on 11st's own policy: no amount field
            }
        }
        List<String> missing = new ArrayList<>();
        for (String key : ElevenstShippingKeys.ALL_KEYS) {   // screen order
            if (!required.contains(key)) {
                continue;
            }
            String value = resolved.get(key);
            String label = ElevenstShippingKeys.LABELS.get(key);
            if ((value == null || value.isBlank()) && !missing.contains(label)) {
                missing.add(label);
            }
        }
        return missing;
    }

    /**
     * D16 · D92 · D106 · D111: the buyer's one-unit shipping fee the 3.3% applies to. Cash on delivery (02) =
     * none; 01 free · 07 · 08 (amount unknown to us) = none; 02 fixed = dlvCst1; 03 = dlvCst1 below the free
     * threshold; 04 = the first tier fee; 05 = the per-unit fee.
     *
     * @throws IllegalArgumentException (→ 400) when the amount for the chosen type is not a number
     */
    public static CustomerShippingFee customerFee(Map<String, String> resolved) {
        if ("02".equals(resolved.get(ElevenstShippingKeys.DLV_CST_PAY_TYP_CD))) {
            return CustomerShippingFee.NONE;
        }
        return switch (String.valueOf(resolved.get(ElevenstShippingKeys.DLV_CST_INST_BASI_CD))) {
            case "02" -> new CustomerShippingFee(amount(resolved, ElevenstShippingKeys.DLV_CST1), null);
            case "03" -> new CustomerShippingFee(amount(resolved, ElevenstShippingKeys.DLV_CST1),
                    amount(resolved, ElevenstShippingKeys.PRD_FR_DLV_BASI_AMT));
            case "04" -> new CustomerShippingFee(firstTierFee(resolved), null);
            case "05" -> new CustomerShippingFee(amount(resolved, ElevenstShippingKeys.DLV_CST4), null);
            default -> CustomerShippingFee.NONE;
        };
    }

    private static BigDecimal firstTierFee(Map<String, String> resolved) {
        String fees = resolved.get(ElevenstShippingKeys.DLV_CST3);
        if (fees == null || fees.isBlank()) {
            return BigDecimal.ZERO;
        }
        return parse(fees.split("\\^", -1)[0], ElevenstShippingKeys.DLV_CST3);
    }

    private static BigDecimal amount(Map<String, String> resolved, String key) {
        String value = resolved.get(key);
        return value == null || value.isBlank() ? BigDecimal.ZERO : parse(value, key);
    }

    private static BigDecimal parse(String value, String key) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "11번가 배송 설정의 금액이 숫자가 아닙니다: " + ElevenstShippingKeys.LABELS.get(key));
        }
    }

    private Map<String, String> baseSettings(ProductListing cell) {
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), Platform.ELEVENST)
                .orElse(null);
        if (account == null) {
            return null;
        }
        return elevenstShippingConfigRepository.findByMarketplaceAccountId(account.getId())
                .map(ElevenstShippingConfig::getSettings)
                .orElse(null);
    }

    private static Map<String, String> masterMap(ProductListing cell) {
        return cell.getMasterProduct() != null ? cell.getMasterProduct().getShippingOverride() : null;
    }

    private static Map<String, String> build(Map<String, String> listing, Map<String, String> master,
                                             Map<String, String> base) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : ElevenstShippingKeys.ALL_KEYS) {
            String value = nonBlank(get(listing, key));
            if (value == null && !ElevenstShippingKeys.PLACE_KEYS.contains(key)) {
                value = nonBlank(get(master, key));
            }
            if (value == null) {
                value = nonBlank(get(base, key));
            }
            if (value != null) {
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    private static String get(Map<String, String> map, String key) {
        return map == null ? null : map.get(key);
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
