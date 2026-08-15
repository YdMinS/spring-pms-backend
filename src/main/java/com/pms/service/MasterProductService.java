package com.pms.service;

import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductResponse;

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
}
