package com.pms.service;

import com.pms.dto.request.CommissionRateRequest;
import com.pms.dto.response.CommissionRateResponse;

import java.math.BigDecimal;
import java.util.List;

public interface CommissionRateService {
    CommissionRateResponse create(CommissionRateRequest request);

    List<CommissionRateResponse> findAll();

    CommissionRateResponse findById(Long id);

    CommissionRateResponse update(Long id, CommissionRateRequest request);

    void delete(Long id);

    BigDecimal findRate(String platform, Long categoryId);
}
