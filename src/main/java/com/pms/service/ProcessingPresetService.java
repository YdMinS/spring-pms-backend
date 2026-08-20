package com.pms.service;

import com.pms.dto.request.ProcessingPresetRequest;
import com.pms.dto.response.ProcessingPresetResponse;

import java.util.List;

/**
 * The tenant's image-processing preset library (FEATURE_2608_08) — mirror of {@link DetailTemplateService}
 * but without a default concept (presets apply only where a template references them).
 *
 * <p>Reads are tenant-scoped via {@code @TenantId}; single-id operations use {@code findScopedById} so a
 * cross-tenant/absent id is 404.</p>
 */
public interface ProcessingPresetService {

    List<ProcessingPresetResponse> list();

    ProcessingPresetResponse get(Long id);

    ProcessingPresetResponse create(ProcessingPresetRequest request);

    ProcessingPresetResponse update(Long id, ProcessingPresetRequest request);

    void delete(Long id);
}
