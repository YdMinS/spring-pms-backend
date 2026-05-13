package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.dto.request.CarrierRateRequest;
import com.pms.dto.response.CarrierRateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CarrierRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CarrierRateServiceImpl implements CarrierRateService {

    private final CarrierRateRepository carrierRateRepository;

    @Override
    @Transactional
    public CarrierRateResponse createCarrierRate(CarrierRateRequest request) {
        if (request.getIsDefault()) {
            carrierRateRepository.findByIsDefaultTrue()
                    .ifPresent(existing -> {
                        existing.setIsDefault(false);
                        carrierRateRepository.save(existing);
                    });
        }

        CarrierRate carrierRate = CarrierRate.builder()
                .carrier(request.getCarrier())
                .type(request.getType())
                .cost(request.getCost())
                .effectiveDate(request.getEffectiveDate())
                .isDefault(request.getIsDefault())
                .build();

        CarrierRate saved = carrierRateRepository.save(carrierRate);
        return mapToResponse(saved);
    }

    @Override
    public CarrierRateResponse getCarrierRate(Long id) {
        CarrierRate carrierRate = carrierRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CarrierRate", id));
        return mapToResponse(carrierRate);
    }

    @Override
    public List<CarrierRateResponse> getCarrierRates() {
        return carrierRateRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public CarrierRateResponse updateCarrierRate(Long id, CarrierRateRequest request) {
        CarrierRate carrierRate = carrierRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CarrierRate", id));

        if (request.getIsDefault()) {
            carrierRateRepository.findByIsDefaultTrue()
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(id)) {
                            existing.setIsDefault(false);
                            carrierRateRepository.save(existing);
                        }
                    });
        }

        carrierRate.setCarrier(request.getCarrier());
        carrierRate.setType(request.getType());
        carrierRate.setCost(request.getCost());
        carrierRate.setEffectiveDate(request.getEffectiveDate());
        carrierRate.setIsDefault(request.getIsDefault());

        CarrierRate updated = carrierRateRepository.save(carrierRate);
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public void deleteCarrierRate(Long id) {
        CarrierRate carrierRate = carrierRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CarrierRate", id));
        carrierRateRepository.delete(carrierRate);
    }

    private CarrierRateResponse mapToResponse(CarrierRate carrierRate) {
        return CarrierRateResponse.builder()
                .id(carrierRate.getId())
                .carrier(carrierRate.getCarrier())
                .type(carrierRate.getType())
                .cost(carrierRate.getCost())
                .effectiveDate(carrierRate.getEffectiveDate())
                .isDefault(carrierRate.getIsDefault())
                .build();
    }
}
