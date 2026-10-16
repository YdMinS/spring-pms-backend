package com.pms.service.elevenst;

import com.pms.dto.request.ElevenstCancelRejectRequest;
import com.pms.dto.response.ElevenstCancelRequestResponse;

import java.util.List;

/**
 * The order detail's 11st cancel requests (FEATURE_2610_17 / D19 · D37 · D38 · D40): list the open ones, approve
 * (1640) or reject (1641). Approve and reject cannot be undone on 11st.
 */
public interface ElevenstCancelRequestService {

    /** Open (status 01) requests of one order line, oldest first. Not an 11st line → empty list. */
    List<ElevenstCancelRequestResponse> listOpen(Long orderItemId);

    /**
     * @throws com.pms.exception.ResourceNotFoundException no such request (→ 404)
     * @throws com.pms.exception.BusinessException        not open any more (→ 409) · 11st refused (→ 502)
     */
    void approve(Long requestId);

    /**
     * @throws IllegalArgumentException carrier not in the 11st list · invoice of 10 characters or fewer (→ 400)
     */
    void reject(Long requestId, ElevenstCancelRejectRequest request);
}
