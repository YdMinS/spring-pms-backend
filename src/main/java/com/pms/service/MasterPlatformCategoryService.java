package com.pms.service;

import com.pms.domain.Platform;
import com.pms.dto.request.MasterPlatformCategoryRequest;
import com.pms.dto.response.MasterPlatformCategoryResponse;

import java.util.List;

/**
 * A master's own platform category — 「개별 지정」 (FEATURE_2610_12 / D40). One row per (master, platform);
 * no row = the standard category mapping is used. The read side lives in {@link MasterChannelConfigService}.
 */
public interface MasterPlatformCategoryService {

    /** The master's own rows (0~N, one per platform). 404 if the master is absent (tenant-scoped). */
    List<MasterPlatformCategoryResponse> getAll(Long masterId);

    /**
     * Insert or replace the master's own row for {@code request.platform}. 404 if the master is absent;
     * 400 if the code is not in that platform's {@code platform_category} list.
     */
    MasterPlatformCategoryResponse upsert(Long masterId, MasterPlatformCategoryRequest request);

    /** Remove the master's own row for the platform (back to the standard category). 404 if there is none. */
    void delete(Long masterId, Platform platform);
}
