package com.pms.service;

import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterProductResponse;

import java.util.List;

/**
 * Read-only access to master products + the channel coverage matrix (FEATURE_2608_06 / 3a).
 *
 * <p>All reads are tenant-scoped via {@code @TenantId} (see {@code MasterProductRepository}).
 * Cross-tenant ids resolve to 404 through the tenant-filtered {@code findScopedById}.</p>
 */
public interface MasterProductService {

    List<MasterProductResponse> getMasterProducts();

    MasterProductResponse getMasterProduct(Long id);

    ListingMatrixResponse getMatrix(Long id);
}
