package com.pms.service;

import com.pms.dto.request.CarrierRateRequest;
import com.pms.dto.response.CarrierRateResponse;

import java.util.List;

public interface CarrierRateService {
    CarrierRateResponse createCarrierRate(CarrierRateRequest request);

    CarrierRateResponse getCarrierRate(Long id);

    List<CarrierRateResponse> getCarrierRates();

    CarrierRateResponse updateCarrierRate(Long id, CarrierRateRequest request);

    void deleteCarrierRate(Long id);
}
