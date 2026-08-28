package com.pms.service.listing.shipping;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MarketplaceShippingConfig;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MarketplaceShippingConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Resolves a channel cell's shipping config (FEATURE_2608_06 / 75) — mirrors {@code OptionCheckSuffixResolver}
 * (69): the account config (72) is the <b>default</b>, and the master ({@link MasterProduct#getShippingOverride()})
 * / channel ({@link ProductListing#getShippingOverride()}) override it per field:
 *
 * <pre>
 *   pick(key) = blankToNull(listing.get(key)) ?? blankToNull(master.get(key)) ?? account default
 * </pre>
 *
 * <p>⚠️ Outbound place + return center are resolved {@code channel ?? account} only (the master step is
 * skipped — those place keys are dropped from the master whitelist, so they can't be in the master map). All
 * other fields (carrier code included) are 3-level. Blank override = inherit (69 field-wise convention).
 * Numeric override values (delivery/return charges) are stored as strings and parsed to {@link BigDecimal}.</p>
 *
 * <p>⚠️ LazyInit: {@link #resolve(ProductListing)} touches {@code cell.masterProduct}/{@code cell.seller}
 * (LAZY), so callers (the register adapter) must be inside a {@code @Transactional} boundary (open-in-view
 * =false). The resolver is otherwise a pure field-wise combination → Mockito unit-tested.</p>
 */
@Component
@RequiredArgsConstructor
public class ShippingConfigResolver {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final MarketplaceShippingConfigRepository shippingConfigRepository;

    public ResolvedShippingConfig resolve(ProductListing cell) {
        return build(cell.getShippingOverride(), masterMap(cell), baseConfig(cell));
    }

    /**
     * The <b>inherited baseline</b> for a cell = {@code master ?? account default}, with the listing's own
     * override <b>excluded</b> (FEATURE_2608_06 / 76). Shown as a placeholder so the user sees what applies
     * when a channel shipping field is left blank. LazyInit note as {@link #resolve(ProductListing)}.
     */
    public ResolvedShippingConfig resolveInherited(ProductListing cell) {
        return build(null, masterMap(cell), baseConfig(cell));
    }

    /** Account default (72): the (seller, platform) account's stored config; absent → null (all-null base). */
    private MarketplaceShippingConfig baseConfig(ProductListing cell) {
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElse(null);
        return account == null ? null
                : shippingConfigRepository.findByMarketplaceAccountId(account.getId()).orElse(null);
    }

    private static Map<String, String> masterMap(ProductListing cell) {
        return cell.getMasterProduct() != null ? cell.getMasterProduct().getShippingOverride() : null;
    }

    private static ResolvedShippingConfig build(Map<String, String> listing, Map<String, String> master,
                                                MarketplaceShippingConfig base) {
        return new ResolvedShippingConfig(
                // outbound place + return center = channel ?? account (master skipped)
                pick2(listing, ShippingOverrideKeys.OUTBOUND_SHIPPING_PLACE_CODE,
                        base == null ? null : base.getOutboundShippingPlaceCode()),
                pick2(listing, ShippingOverrideKeys.RETURN_CENTER_CODE,
                        base == null ? null : base.getReturnCenterCode()),
                pick2(listing, ShippingOverrideKeys.RETURN_CHARGE_NAME,
                        base == null ? null : base.getReturnChargeName()),
                pick2(listing, ShippingOverrideKeys.RETURN_CONTACT_NUMBER,
                        base == null ? null : base.getReturnContactNumber()),
                pick2(listing, ShippingOverrideKeys.RETURN_ZIP_CODE,
                        base == null ? null : base.getReturnZipCode()),
                pick2(listing, ShippingOverrideKeys.RETURN_ADDRESS,
                        base == null ? null : base.getReturnAddress()),
                pick2(listing, ShippingOverrideKeys.RETURN_ADDRESS_DETAIL,
                        base == null ? null : base.getReturnAddressDetail()),
                pickBig(listing, master, ShippingOverrideKeys.RETURN_CHARGE,
                        base == null ? null : base.getReturnCharge()),
                pickBig(listing, master, ShippingOverrideKeys.DELIVERY_CHARGE_ON_RETURN,
                        base == null ? null : base.getDeliveryChargeOnReturn()),
                // delivery settings = channel ?? master ?? account
                pick3(listing, master, ShippingOverrideKeys.DELIVERY_METHOD,
                        base == null ? null : base.getDeliveryMethod()),
                pick3(listing, master, ShippingOverrideKeys.DELIVERY_COMPANY_CODE,
                        base == null ? null : base.getDeliveryCompanyCode()),
                pick3(listing, master, ShippingOverrideKeys.DELIVERY_CHARGE_TYPE,
                        base == null ? null : base.getDeliveryChargeType()),
                pickBig(listing, master, ShippingOverrideKeys.DELIVERY_CHARGE,
                        base == null ? null : base.getDeliveryCharge()),
                pickBig(listing, master, ShippingOverrideKeys.FREE_SHIP_OVER_AMOUNT,
                        base == null ? null : base.getFreeShipOverAmount()),
                pick3(listing, master, ShippingOverrideKeys.REMOTE_AREA_DELIVERABLE,
                        base == null ? null : base.getRemoteAreaDeliverable()),
                pick3(listing, master, ShippingOverrideKeys.UNION_DELIVERY_TYPE,
                        base == null ? null : base.getUnionDeliveryType()),
                pick3(listing, master, ShippingOverrideKeys.EXTRA_INFO_MESSAGE,
                        base == null ? null : base.getExtraInfoMessage()));
    }

    /** channel ?? master ?? account (string). */
    private static String pick3(Map<String, String> listing, Map<String, String> master, String key,
                                String fallback) {
        String v = blankToNull(get(listing, key));
        if (v != null) {
            return v;
        }
        v = blankToNull(get(master, key));
        return v != null ? v : fallback;
    }

    /** channel ?? account (string) — master step skipped (place keys). */
    private static String pick2(Map<String, String> listing, String key, String fallback) {
        String v = blankToNull(get(listing, key));
        return v != null ? v : fallback;
    }

    /** channel ?? master ?? account (numeric) — override strings parsed to BigDecimal. */
    private static BigDecimal pickBig(Map<String, String> listing, Map<String, String> master, String key,
                                      BigDecimal fallback) {
        String v = blankToNull(get(listing, key));
        if (v == null) {
            v = blankToNull(get(master, key));
        }
        return v != null ? new BigDecimal(v) : fallback;
    }

    private static String get(Map<String, String> map, String key) {
        return map == null ? null : map.get(key);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
