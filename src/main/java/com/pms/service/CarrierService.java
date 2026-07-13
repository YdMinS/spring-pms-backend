package com.pms.service;

import com.pms.dto.request.CarrierRequest;
import com.pms.dto.response.CarrierResponse;

import java.util.List;

public interface CarrierService {
    CarrierResponse createCarrier(CarrierRequest request);

    CarrierResponse getCarrier(Long id);

    List<CarrierResponse> getCarriers();

    CarrierResponse updateCarrier(Long id, CarrierRequest request);

    void deleteCarrier(Long id);
}
