package com.pms.service.lotteon;

import com.pms.dto.request.LotteonCancelRejectRequest;
import com.pms.dto.response.LotteonCancelRequestResponse;

import java.util.List;

/**
 * The order detail's LotteON cancel requests (FEATURE_2611_23 / D24 · D71 · R22 · R24): list the open ones, approve
 * ([60]) or reject ([59]). Neither can be undone on LotteON.
 */
public interface LotteonCancelRequestService {

    /** Open (status 02) requests of one order line, oldest first. Not a LotteON line → empty list. */
    List<LotteonCancelRequestResponse> listOpen(Long orderItemId);

    /**
     * Approves with the request's own reason code (D24).
     *
     * @throws com.pms.exception.ResourceNotFoundException no such request (→ 404)
     * @throws com.pms.exception.BusinessException        not open any more (→ 409) · LotteON refused (→ 502)
     */
    void approve(Long requestId);

    /**
     * @throws IllegalArgumentException reason code not in the list (→ 400, nothing is sent)
     * @throws com.pms.exception.BusinessException not open any more (→ 409) · LotteON refused (→ 502)
     */
    void reject(Long requestId, LotteonCancelRejectRequest request);
}
