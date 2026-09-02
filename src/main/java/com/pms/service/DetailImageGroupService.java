package com.pms.service;

import com.pms.dto.request.DetailImageGroupRequest;
import com.pms.dto.response.DetailImageGroupResponse;

import java.util.List;

/**
 * The tenant's detail image group catalog (FEATURE_2609_03) — the shared list of detail-page image zones
 * that {@link DetailTemplateService} validates {@code blocks[].bind} against.
 *
 * <p>Reads are tenant-scoped via {@code @TenantId}; single-id operations use {@code findScopedById} so a
 * cross-tenant/absent id is 404.</p>
 *
 * <p>⚠️ There is no {@code code} update: the code is the mapping key stored on every master photo
 * assignment, so only {@link #rename} (display name) exists. See {@code DetailImageGroup}.</p>
 */
public interface DetailImageGroupService {

    List<DetailImageGroupResponse> list();

    DetailImageGroupResponse create(DetailImageGroupRequest request);

    /** Change the display name only — {@code code} and {@code sortOrder} stay as they are. */
    DetailImageGroupResponse rename(Long id, DetailImageGroupRequest request);

    /**
     * Delete a group that no active template uses. The zone's photo mappings are dropped with it; the pool
     * images themselves are never touched.
     */
    void delete(Long id);
}
