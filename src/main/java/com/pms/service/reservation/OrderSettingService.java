package com.pms.service.reservation;

import com.pms.dto.request.OrderSettingRequest;
import com.pms.dto.response.OrderSettingResponse;

import java.time.LocalDateTime;

/** 주문관리 설정(테넌트 단위, FEATURE_2609_75 / D4 · D12). */
public interface OrderSettingService {

    OrderSettingResponse get();

    OrderSettingResponse update(OrderSettingRequest request);

    /** 기본 예약 시각의 다음 도래 시각(KST). 지금보다 늦은 가장 가까운 그 시각. */
    LocalDateTime nextExecuteAt();
}
