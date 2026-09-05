package com.pms.service;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.DetailPreviewResponse;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.dto.response.GeneratedProductResponse;
import com.pms.dto.response.ShippingConfigResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Auto-generation of a channel cell's assets (FEATURE_2608_06 / 3b-2): thumbnail, detail HTML (seam
 * stub), and per-option selling prices (margin reverse-calc).
 *
 * <p>{@link #regenerateAssets(ProductListing)} is the shared seam — first run creates the assets,
 * propagation (3d) re-runs the same code path. The endpoint-facing {@link #regenerate(Long)} /
 * {@link #getGenerated(Long)} resolve the tenant-scoped cell first (404 for a cross-tenant/absent id).</p>
 */
public interface ListingAssetService {

    /** Endpoint 4-1: regenerate + persist assets for a tenant-scoped cell (404 if absent). */
    GeneratedProductResponse regenerate(Long listingId);

    /** Endpoint 4-2: read persisted assets (404 if the cell is absent or not yet generated). */
    GeneratedProductResponse getGenerated(Long listingId);

    /** Non-persistent AUTO detail-HTML preview for a tenant-scoped cell (404 if absent); ignores any override. */
    DetailPreviewResponse previewDetail(Long listingId);

    /**
     * The detail template actually applied to a tenant-scoped cell (404 if absent): the account-assigned
     * template ?? the tenant default, via {@link ChannelTemplateResolver#resolveDetail(ProductListing)}
     * (SSOT — the fallback rule is not re-implemented in the controller/frontend). Read-only; the frontend
     * derives its zone/text keys from this instead of hard-coding {@code isDefault} (FEATURE_2608_06 / 29).
     */
    DetailTemplateResponse resolveDetailTemplate(Long listingId);

    /** Upsert a raw-HTML override (source=MANUAL_OVERRIDE) for a tenant-scoped cell (404 if absent). */
    GeneratedProductResponse overrideDetailHtml(Long listingId, String html);

    /** Drop the override (source=AUTO) and re-apply generator output (404 if cell/assets absent). */
    GeneratedProductResponse clearDetailHtml(Long listingId);

    /**
     * Save the channel-level text field-value overrides for a tenant-scoped cell (404 if absent) and
     * regenerate its assets (FEATURE_2608_06 / 12). An empty map clears the override. The detail-HTML
     * override guard is unchanged (a MANUAL_OVERRIDE cell keeps its edited detail HTML).
     */
    GeneratedProductResponse updateFieldValues(Long listingId, Map<String, String> fieldValues);

    /**
     * Replace a tenant-scoped cell's raw channel tags (33; 404 if absent). The list is order-preserving
     * deduped; an empty list clears them. No regeneration/push — the merged snapshot is recorded at push
     * time. Returns the cell's current generated view (asset fields null if not yet generated).
     */
    GeneratedProductResponse updateTags(Long listingId, List<String> tags);

    /**
     * Update the display name (노출상품명 = {@code ProductListing.name}) of a tenant-scoped cell (404 if
     * absent). No asset regeneration and no marketplace push here (the name is not a thumbnail/detail binding
     * key) — but the value <b>is</b> sent to the market as {@code displayProductName} on the next register /
     * [수정 요청] (108/D2 reverses 35's "internal only"). The name is trimmed before saving (35).
     */
    void updateDisplayName(Long listingId, String name);

    /**
     * Replace a tenant-scoped cell's channel-level shipping overrides (75; 404 if absent). Key whitelist only
     * (register 72/73 is the final value guard); an empty/null map clears the override. No asset regeneration
     * (shipping is not a thumbnail/detail/price binding). Returns the cell's current generated view.
     */
    GeneratedProductResponse updateShippingOverride(Long listingId, Map<String, String> override);

    /**
     * The inherited shipping baseline for a tenant-scoped cell (404 if absent) = {@code master ?? account
     * default}, with the cell's own channel override excluded (FEATURE_2608_06 / 76). The frontend shows
     * these as placeholders so the user sees what applies when a channel field is left blank. Read-only.
     */
    ShippingConfigResponse resolveInheritedShipping(Long listingId);

    /**
     * Override the cell's thumbnail with an uploaded image (thumbnailSource=MANUAL_OVERRIDE) for a
     * tenant-scoped cell. 404 if the cell or its generated assets are absent (the cell must be generated
     * first via the matrix). Detail HTML and its source are untouched (the two origins are independent).
     */
    GeneratedProductResponse overrideThumbnail(Long listingId, MultipartFile file);

    /**
     * Drop the thumbnail override (thumbnailSource=AUTO) and re-render for a tenant-scoped cell. 404 if the
     * cell or its generated assets are absent. Detail HTML and its source are untouched.
     */
    GeneratedProductResponse clearThumbnail(Long listingId);

    /**
     * Seam: (re)generate the thumbnail + detail HTML + per-option selling prices for {@code cell} and
     * upsert its {@link GeneratedProductData}. Called by {@link #regenerate(Long)} and (later) 3d
     * propagation. One transaction.
     */
    GeneratedProductData regenerateAssets(ProductListing cell);

    /**
     * Narrow seam: recompute and persist only the per-option {@code sellingPrice} / {@code originalPrice}
     * of {@code cell} (margin reverse-calc). Step 3 of {@link #regenerateAssets(ProductListing)}, extracted
     * so a master option quantity edit can re-sync channel prices WITHOUT the thumbnail / detail work
     * (FEATURE_2608_06 / 84): re-rendering would cost an S3 GET + Java2D render + S3 PUT per cell (plus
     * every zone image when a processing preset is attached) for a one-line option change.
     *
     * <p>⚠️ Runs in the caller's transaction (default {@code REQUIRED} — no new boundary), so a caller that
     * just saved BOM quantities sees them here via JPA auto-flush.</p>
     *
     * <p>⚠️ Options whose {@code priceSource} is {@code MANUAL_OVERRIDE} are skipped (2609_19/D2): a price
     * the user set for that one channel survives a regeneration.</p>
     */
    void recalculateOptionPrices(ProductListing cell);

    /**
     * The auto-calculated price of ONE option — <b>nothing is persisted</b> (FEATURE_2609_19 / D3). This
     * exists because "[기본값으로 변경]" has to know the value a manual price returns to <em>before</em>
     * anything is saved or pushed to the market. Same formula as {@link #recalculateOptionPrices} (they
     * share one private helper — the expression is not duplicated).
     *
     * <p>⚠️ Do not use {@code recalculateOptionPrices} for that: it saves inside its loop, which would
     * persist the restored price before the market call and break "save only when the market accepted it"
     * (D6).</p>
     *
     * @throws IllegalArgumentException (→400) when category / commission / delivery / box / margin are unset
     *         (raised by {@link PriceCalculator})
     */
    PriceCalculator.PriceResult quoteOptionPrice(ProductListing cell, ProductListingOption option);
}
