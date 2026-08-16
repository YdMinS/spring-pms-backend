package com.pms.service;

import com.pms.dto.response.DetailTemplateResponse;

import java.util.List;

/**
 * Read access to the tenant's detail-page template library (FEATURE_2608_06 / Step 2-1).
 *
 * <p>Read-only in this step — block editing / create is a later editor. Reads are tenant-scoped via
 * {@code @TenantId}; {@link #get(Long)} uses {@code findScopedById} so a cross-tenant/absent id is 404.</p>
 */
public interface DetailTemplateService {

    List<DetailTemplateResponse> list();

    DetailTemplateResponse get(Long id);
}
