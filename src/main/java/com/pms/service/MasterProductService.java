package com.pms.service;

import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
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

    ListingMatrixResponse getMatrix(Long id);

    MasterProductResponse createMasterProduct(MasterProductRequest request);

    MasterProductResponse updateMasterProduct(Long id, MasterProductUpdateRequest request);

    /** Soft delete: sets {@code active=false} (restore via PATCH {@code active=true}). */
    void deleteMasterProduct(Long id);

    MasterOptionResponse createOption(Long masterId, MasterOptionRequest request);

    MasterOptionResponse updateOption(Long masterId, Long optionId, MasterOptionRequest request);

    void deleteOption(Long masterId, Long optionId);

    /** Upsert the category for (master, platform) — FEATURE_2608_06 / 13. 404 if master/category absent. */
    MasterCategoryResponse upsertCategory(Long masterId, MasterCategoryRequest request);

    /** List the master's per-platform categories. */
    List<MasterCategoryResponse> getCategories(Long masterId);

    /** Delete the master's category for a platform (404 if none). */
    void deleteCategory(Long masterId, String platform);

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
