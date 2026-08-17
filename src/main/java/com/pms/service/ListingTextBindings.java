package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure text-binding assembly shared by the thumbnail path ({@link ListingAssetServiceImpl}) and the
 * detail generator ({@link TemplateDetailContentGenerator}) — FEATURE_2608_06 / Step 2-2.
 *
 * <p>Produces the master field values (blank entries dropped), then derives the two reserved keys
 * ({@code brandName} / {@code productName}) from the cell's first BOM product only when they are absent.
 * ⚠️ NO {@code defaultValue} fallback here — that is {@link DetailHtmlRenderer}'s single responsibility;
 * the thumbnail path applies its own template-field defaults on top of these pure bindings.</p>
 */
final class ListingTextBindings {

    /** Reserved template field keys → registered-product info fallback. */
    static final String KEY_BRAND = "brandName";
    static final String KEY_PRODUCT_NAME = "productName";

    private ListingTextBindings() {
    }

    /**
     * Channel-override overload (FEATURE_2608_06 / 12): the master/product base {@link #resolve(MasterProduct,
     * Product)} with the cell's own {@code fieldValues} (non-blank) layered on top per key. Resulting priority
     * per key: {@code listing.fieldValues (non-blank) > master.fieldValues (non-blank) > reserved-key product
     * value}. Blank keys are left for each renderer's template {@code defaultValue} fallback (07 §3, unchanged).
     */
    static Map<String, String> resolve(ProductListing cell, MasterProduct master, Product firstProduct) {
        Map<String, String> bindings = resolve(master, firstProduct);
        Map<String, String> overrides = cell == null ? null : cell.getFieldValues();
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                if (StringUtils.hasText(e.getKey()) && StringUtils.hasText(e.getValue())) {
                    bindings.put(e.getKey(), e.getValue());
                }
            }
        }
        return bindings;
    }

    /** master.fieldValues (non-blank) + derived brandName/productName from {@code firstProduct} (if absent). */
    static Map<String, String> resolve(MasterProduct master, Product firstProduct) {
        Map<String, String> bindings = new HashMap<>();
        Map<String, String> fieldValues = master == null ? null : master.getFieldValues();
        if (fieldValues != null) {
            for (Map.Entry<String, String> e : fieldValues.entrySet()) {
                if (StringUtils.hasText(e.getKey()) && StringUtils.hasText(e.getValue())) {
                    bindings.put(e.getKey(), e.getValue());
                }
            }
        }
        if (firstProduct != null) {
            putDerived(bindings, KEY_BRAND, firstProduct.getBrand());
            putDerived(bindings, KEY_PRODUCT_NAME, firstProduct.getProductName());
        }
        return bindings;
    }

    /** Fill a reserved key from product info only when it is not already present and the value is non-blank. */
    private static void putDerived(Map<String, String> bindings, String key, String value) {
        if (!bindings.containsKey(key) && StringUtils.hasText(value)) {
            bindings.put(key, value);
        }
    }
}
