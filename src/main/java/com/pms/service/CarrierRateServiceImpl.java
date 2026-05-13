package com.pms.service;

import com.pms.dto.request.CarrierRateRequest;
import com.pms.dto.response.CarrierRateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CarrierRateServiceImpl implements CarrierRateService {

    @Override
    @Transactional
    public CarrierRateResponse createCarrierRate(CarrierRateRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CarrierRateResponse getCarrierRate(Long id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CarrierRateResponse> getCarrierRates() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Transactional
    public CarrierRateResponse updateCarrierRate(Long id, CarrierRateRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    @Transactional
    public void deleteCarrierRate(Long id) {
        throw new UnsupportedOperationException();
    }
}
