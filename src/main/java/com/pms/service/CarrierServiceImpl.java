package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.dto.request.CarrierRequest;
import com.pms.dto.response.CarrierResponse;
import com.pms.exception.CarrierNotFoundException;
import com.pms.repository.CarrierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CarrierServiceImpl implements CarrierService {

    private final CarrierRepository carrierRepository;

    @Override
    @Transactional
    public CarrierResponse createCarrier(CarrierRequest request) {
        Carrier carrier = Carrier.builder()
                .name(request.getName())
                .isActive(request.getIsActive())
                .build();
        Carrier saved = carrierRepository.save(carrier);
        return toResponse(saved);
    }

    @Override
    public CarrierResponse getCarrier(Long id) {
        Carrier carrier = carrierRepository.findById(id)
                .orElseThrow(() -> new CarrierNotFoundException(id));
        return toResponse(carrier);
    }

    @Override
    public List<CarrierResponse> getCarriers() {
        return carrierRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public CarrierResponse updateCarrier(Long id, CarrierRequest request) {
        Carrier carrier = carrierRepository.findById(id)
                .orElseThrow(() -> new CarrierNotFoundException(id));

        // Full-replace: name/isActive 둘 다 교체 (부분 수정 아님).
        Carrier updated = carrier.toBuilder()
                .name(request.getName())
                .isActive(request.getIsActive())
                .build();

        Carrier saved = carrierRepository.save(updated);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteCarrier(Long id) {
        Carrier carrier = carrierRepository.findById(id)
                .orElseThrow(() -> new CarrierNotFoundException(id));
        carrierRepository.delete(carrier);
    }

    private CarrierResponse toResponse(Carrier carrier) {
        return CarrierResponse.builder()
                .id(carrier.getId())
                .name(carrier.getName())
                .isActive(carrier.getIsActive())
                .build();
    }
}
