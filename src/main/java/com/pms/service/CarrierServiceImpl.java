package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.dto.request.CarrierRequest;
import com.pms.dto.response.CarrierResponse;
import com.pms.exception.CarrierInUseException;
import com.pms.exception.CarrierNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CarrierRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
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
    private final CarrierRateRepository carrierRateRepository;
    private final PlatformCarrierCodeRepository platformCarrierCodeRepository;

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
        // FK 가드: 요율이 참조 중이면 삭제 불가 → 409. (요율은 cascade 대상 아님)
        if (carrierRateRepository.existsByCarrierId(id)) {
            throw new CarrierInUseException(id);
        }
        // 소유 자식인 플랫폼 코드는 cascade 삭제 후 carrier 삭제(FK 위반 방지).
        platformCarrierCodeRepository.deleteByCarrier_Id(id);
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
