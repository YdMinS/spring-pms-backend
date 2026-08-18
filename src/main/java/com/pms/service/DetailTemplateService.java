package com.pms.service;

import com.pms.dto.request.DetailTemplateRequest;
import com.pms.dto.response.DetailTemplateResponse;

import java.util.List;

/**
 * The tenant's editable detail-page template library (FEATURE_2608_06 / 17) — mirror of
 * {@link ThumbnailTemplateService}. Templates are a tenant-wide shared library (not seller-owned) with
 * exactly one default ({@code isDefault=true}).
 *
 * <p>Reads are tenant-scoped via {@code @TenantId}; single-id operations use {@code findScopedById} so a
 * cross-tenant/absent id is 404.</p>
 */
public interface DetailTemplateService {

    List<DetailTemplateResponse> list();

    DetailTemplateResponse get(Long id);

    DetailTemplateResponse create(DetailTemplateRequest request);

    DetailTemplateResponse update(Long id, DetailTemplateRequest request);

    void delete(Long id);
}
