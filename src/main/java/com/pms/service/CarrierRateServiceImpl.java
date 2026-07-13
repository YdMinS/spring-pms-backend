package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.domain.CarrierRate;
import com.pms.dto.request.CarrierRateRequest;
import com.pms.dto.response.CarrierRateResponse;
import com.pms.exception.CarrierNotFoundException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CarrierRateServiceImpl implements CarrierRateService {

    private final CarrierRateRepository carrierRateRepository;
    private final CarrierRepository carrierRepository;

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

        Carrier carrier = carrierRepository.findById(request.getCarrierId())
                .orElseThrow(() -> new CarrierNotFoundException(request.getCarrierId()));

        CarrierRate carrierRate = CarrierRate.builder()
                .carrier(carrier)
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

        // carrierId 항상 재조회하여 연결 (요율의 소속 택배사 변경 허용).
        Carrier carrier = carrierRepository.findById(request.getCarrierId())
                .orElseThrow(() -> new CarrierNotFoundException(request.getCarrierId()));

        CarrierRate updated = CarrierRate.builder()
                .id(carrierRate.getId())
                .carrier(carrier)
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
        // carrier is LAZY → call within @Transactional scope to avoid LazyInitializationException.
        return CarrierRateResponse.builder()
                .id(carrierRate.getId())
                .carrierId(carrierRate.getCarrier().getId())
                .carrier(carrierRate.getCarrier().getName())
                .type(carrierRate.getType())
                .cost(carrierRate.getCost())
                .effectiveDate(carrierRate.getEffectiveDate())
                .isDefault(carrierRate.getIsDefault())
                .build();
    }
}
