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
                        CarrierRate updated = CarrierRate.builder()
                                .id(existing.getId())
                                .carrier(existing.getCarrier())
                                .type(existing.getType())
                                .cost(existing.getCost())
                                .effectiveDate(existing.getEffectiveDate())
                                .isDefault(false)
                                .build();
                        carrierRateRepository.save(updated);
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
                            CarrierRate updated = CarrierRate.builder()
                                    .id(existing.getId())
                                    .carrier(existing.getCarrier())
                                    .type(existing.getType())
                                    .cost(existing.getCost())
                                    .effectiveDate(existing.getEffectiveDate())
                                    .isDefault(false)
                                    .build();
                            carrierRateRepository.save(updated);
                        }
                    });
        }

        CarrierRate updated = CarrierRate.builder()
                .id(carrierRate.getId())
                .carrier(request.getCarrier())
                .type(request.getType())
                .cost(request.getCost())
                .effectiveDate(request.getEffectiveDate())
                .isDefault(request.getIsDefault())
                .build();

        CarrierRate saved = carrierRateRepository.save(updated);
        return mapToResponse(saved);
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
