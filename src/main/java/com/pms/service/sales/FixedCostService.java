package com.pms.service.sales;

import com.pms.dto.request.AccountFixedCostReplaceRequest;
import com.pms.dto.request.PlatformFixedCostPatchRequest;
import com.pms.dto.request.PlatformFixedCostRequest;
import com.pms.dto.response.AccountFixedCostResponse;
import com.pms.dto.response.PlatformFixedCostResponse;

import java.util.List;

/**
 * 고정비 카탈로그 CRUD + 채널 연결 (FEATURE_2609_33 / PLAN 2609_33 D1 · D2 · D5 · D10).
 *
 * <p>부과 판정·합계는 여기 없다 — 그쪽 소유자는 {@link FixedCostCalculator} 하나다.
 */
public interface FixedCostService {

    List<PlatformFixedCostResponse> list();

    PlatformFixedCostResponse create(PlatformFixedCostRequest request);

    PlatformFixedCostResponse update(Long id, PlatformFixedCostPatchRequest request);

    void delete(Long id);

    List<AccountFixedCostResponse> listForAccount(Long accountId);

    List<AccountFixedCostResponse> replaceForAccount(Long accountId,
                                                     AccountFixedCostReplaceRequest request);
}
