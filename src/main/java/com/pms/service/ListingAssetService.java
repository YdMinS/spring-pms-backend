package com.pms.service;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ProductListing;
import com.pms.dto.response.DetailPreviewResponse;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.dto.response.GeneratedProductResponse;
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
     * absent). Internal only — no asset regeneration and no marketplace push (the name is not a thumbnail/
     * detail binding key). The name is trimmed before saving (35).
     */
    void updateDisplayName(Long listingId, String name);

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
}
