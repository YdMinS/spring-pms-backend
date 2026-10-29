package com.pms.service.listing;

import com.pms.dto.response.MasterApprovalRefreshResponse;

import java.util.List;

/**
 * Master-wide approval refresh (FEATURE_2610_38): runs the single-listing [승인 새로고침]
 * ({@link ListingRegistrationService#fetchStatus}) for the listings of one master the user picked.
 *
 * <p>⚠️ The implementation is NOT {@code @Transactional}: each {@code fetchStatus} call goes through the other
 * bean's proxy and commits on its own, so one failed listing never rolls the others back (D8 — the same
 * rollback-only trap as {@link MasterPropagationService}).</p>
 */
public interface MasterApprovalRefreshService {

    /**
     * Refresh the picked listings one by one, in the order received (D7 · D10).
     *
     * @param masterId   master product id (tenant-scoped; absent or another tenant's → 404)
     * @param listingIds the picked listing ids; any id that is not a listing of this master → 400 and nothing is
     *                   called (D9)
     * @return four counts + one line per received id
     */
    MasterApprovalRefreshResponse refresh(Long masterId, List<Long> listingIds);
}
