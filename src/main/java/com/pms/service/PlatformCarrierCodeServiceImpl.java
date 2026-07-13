package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.domain.PlatformCarrierCode;
import com.pms.dto.request.PlatformCarrierCodeRequest;
import com.pms.dto.response.PlatformCarrierCodeResponse;
import com.pms.exception.CarrierNotFoundException;
import com.pms.exception.DuplicatePlatformCarrierCodeException;
import com.pms.exception.PlatformCarrierCodeNotFoundException;
import com.pms.repository.CarrierRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 플랫폼별 택배사 코드 관리 CRUD 구현.
 *
 * ⚠️ 발송처리 조회 전용 {@link CarrierCodeService}(lookup)와 구분됨 — 이건 관리(등록/수정/삭제)용.
 * carrier 하위 중첩 리소스라 모든 작업은 carrierId 소속을 검증한다(경로 불일치 = 404).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformCarrierCodeServiceImpl implements PlatformCarrierCodeService {

    private final CarrierRepository carrierRepository;
    private final PlatformCarrierCodeRepository platformCarrierCodeRepository;

    @Override
    public List<PlatformCarrierCodeResponse> getCodes(Long carrierId) {
        if (!carrierRepository.existsById(carrierId)) {
            throw new CarrierNotFoundException(carrierId);
        }
        return platformCarrierCodeRepository.findByCarrier_IdOrderByPlatformAsc(carrierId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public PlatformCarrierCodeResponse createCode(Long carrierId, PlatformCarrierCodeRequest req) {
        Carrier carrier = carrierRepository.findById(carrierId)
                .orElseThrow(() -> new CarrierNotFoundException(carrierId));

        if (platformCarrierCodeRepository.existsByCarrier_IdAndPlatform(carrierId, req.getPlatform())) {
            throw new DuplicatePlatformCarrierCodeException(carrierId, req.getPlatform());
        }

        PlatformCarrierCode saved = platformCarrierCodeRepository.save(
                PlatformCarrierCode.builder()
                        .carrier(carrier)
                        .platform(req.getPlatform())
                        .deliveryCompanyCode(req.getDeliveryCompanyCode())
                        .build());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public PlatformCarrierCodeResponse updateCode(Long carrierId, Long codeId,
                                                  PlatformCarrierCodeRequest req) {
        PlatformCarrierCode code = platformCarrierCodeRepository.findById(codeId)
                .orElseThrow(() -> new PlatformCarrierCodeNotFoundException(codeId));

        // 소속 검증: 경로의 carrierId 와 코드의 소유 carrier 가 다르면 404(리소스 노출 방지).
        if (!code.getCarrier().getId().equals(carrierId)) {
            throw new PlatformCarrierCodeNotFoundException(codeId);
        }

        if (platformCarrierCodeRepository
                .existsByCarrier_IdAndPlatformAndIdNot(carrierId, req.getPlatform(), codeId)) {
            throw new DuplicatePlatformCarrierCodeException(carrierId, req.getPlatform());
        }

        // 영속 엔티티에 도메인 변경 메서드 호출 → dirty checking 으로 반영(새 인스턴스 build/save 금지).
        code.updateCode(req.getPlatform(), req.getDeliveryCompanyCode());
        return toResponse(code);
    }

    @Override
    @Transactional
    public void deleteCode(Long carrierId, Long codeId) {
        PlatformCarrierCode code = platformCarrierCodeRepository.findById(codeId)
                .orElseThrow(() -> new PlatformCarrierCodeNotFoundException(codeId));

        if (!code.getCarrier().getId().equals(carrierId)) {
            throw new PlatformCarrierCodeNotFoundException(codeId);
        }

        platformCarrierCodeRepository.delete(code);
    }

    private PlatformCarrierCodeResponse toResponse(PlatformCarrierCode code) {
        return PlatformCarrierCodeResponse.builder()
                .id(code.getId())
                .carrierId(code.getCarrier().getId())
                .platform(code.getPlatform())
                .deliveryCompanyCode(code.getDeliveryCompanyCode())
                .build();
    }
}
