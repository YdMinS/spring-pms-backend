package com.pms.service;

import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterCategoryResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Master product definition + reads + the channel coverage matrix (FEATURE_2608_06 / 3a, 3b-1).
 *
 * <p>Reads are tenant-scoped via {@code @TenantId} (see {@code MasterProductRepository}); cross-tenant
 * ids resolve to 404 through the tenant-filtered {@code findScopedById}. 3b-1 adds master CRUD (soft
 * delete) plus option CRUD with component-coverage validation.</p>
 */
public interface MasterProductService {

    List<MasterProductResponse> getMasterProducts();

    MasterProductResponse getMasterProduct(Long id);

    /**
     * Whether the master is a mixed-composition (AB) product (FEATURE_2608_06 / 63). Determined by the master's
     * component count: {@code >= 2} components → AB (bundle), {@code 1} → SINGLE, {@code masterId == null} →
     * SINGLE (backfill transition window). Platform-neutral domain fact (component count) — channel adapters
     * reuse it (Coupang skips attributes for AB; NAVER may reuse the judgment too).
     *
     * @param masterId the master product id (nullable → false)
     * @return true if AB (2+ components), false otherwise
     */
    boolean isBundle(Long masterId);

    ListingMatrixResponse getMatrix(Long id);

    MasterProductResponse createMasterProduct(MasterProductRequest request);

    MasterProductResponse updateMasterProduct(Long id, MasterProductUpdateRequest request);

    /**
     * Replace the master's tag pool (33). The list is order-preserving deduped; an empty list clears it.
     *
     * @param id   master product id (tenant-scoped; 404 if absent)
     * @param tags the new tag pool (deduped on save)
     * @return the updated master product (with {@code tags} exposed)
     */
    MasterProductResponse updateTags(Long id, java.util.List<String> tags);

    /**
     * Replace the master-level "옵션확인" suffix override (69). Replace semantics: both fields stored as sent,
     * null = inherit (blank suffix normalizes to null). 404 if the master is absent.
     */
    MasterProductResponse updateRegistrationNameSuffix(Long id, OptionCheckSuffixRequest req);

    /**
     * Replace the master-level shipping overrides (75). Key whitelist only (place keys silently dropped —
     * they are channel-level); null/empty map clears the override. 404 if the master is absent.
     */
    MasterProductResponse updateShippingOverride(Long id, java.util.Map<String, String> override);

    /**
     * Force the master's shipping settings onto the selected channels (FEATURE_2608_06 / 77, semantics revised
     * in 79): each selected cell's own shipping override is <b>overwritten</b> with the master's — the master's
     * master-level keys are written onto the cell, and master-level keys the master leaves empty are removed
     * from the cell. After this a cell's shipping settings are exactly the master's.
     *
     * <p>⚠️ This <b>writes values into the channel</b>, it does not merely clear it: the cell now owns those
     * values, so a later master edit no longer reaches it (the {@code channel ?? master ?? account} priority is
     * unchanged — the channel simply wins with the copied values). Place keys (outbound / return center) are
     * account-specific and are <b>kept</b> untouched. Idempotent: a cell already equal to the master is not
     * saved and not counted.</p>
     *
     * @param id         master product id (404 when absent / cross-tenant)
     * @param listingIds channels to apply to; {@code null}/empty = every linked channel. An id outside this
     *                   master's channels is rejected with 400.
     * @return how many channel cells actually changed
     */
    int applyShippingOverrideToChannels(Long id, java.util.List<Long> listingIds);

    /** Soft delete: sets {@code active=false} (restore via PATCH {@code active=true}). */
    void deleteMasterProduct(Long id);

    MasterOptionResponse createOption(Long masterId, MasterOptionRequest request);

    MasterOptionResponse updateOption(Long masterId, Long optionId, MasterOptionRequest request);

    void deleteOption(Long masterId, Long optionId);

    /** Set the master's single standard category (FEATURE_2608_06 / 44). 404 if master/category absent. */
    MasterCategoryResponse setCategory(Long masterId, MasterCategoryRequest request);

    /** Get the master's standard category (both fields null if unset). */
    MasterCategoryResponse getCategory(Long masterId);

    /** Clear the master's standard category (idempotent; sets it to null). */
    void clearCategory(Long masterId);

    /**
     * Upload a base-image override (FEATURE_2608_06 / 3b-2): validates + stores the file and sets
     * {@code MasterProduct.sourceImageUrl}. Asset regeneration is a separate call (listing regenerate).
     *
     * @param id   master product id (tenant-scoped; 404 if absent)
     * @param file image file (validated by {@code ImageValidator})
     * @return the updated master product
     */
    MasterProductResponse uploadMasterImage(Long id, MultipartFile file);
}
