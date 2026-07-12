package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.domain.PlatformCarrierCode;
import com.pms.repository.CarrierRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link CarrierCodeService} 구현.
 *
 * 택배사 하나 전제(2026-07-12 결정). 활성 택배사가 2개 이상이면 결정적으로 최소 id 행을 사용하고 warn 로그를 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class CarrierCodeServiceImpl implements CarrierCodeService {

    private final CarrierRepository carrierRepository;
    private final PlatformCarrierCodeRepository platformCarrierCodeRepository;

    @Override
    public String resolveDeliveryCompanyCode(String platform) {
        List<Carrier> activeCarriers = carrierRepository.findByIsActiveTrueOrderByIdAsc();
        if (activeCarriers.isEmpty()) {
            throw new IllegalStateException("활성 택배사가 없습니다");
        }
        if (activeCarriers.size() > 1) {
            log.warn("활성 택배사가 {}개입니다 — 최소 id 행을 사용합니다 (택배사 하나 전제)", activeCarriers.size());
        }
        Carrier carrier = activeCarriers.get(0);

        PlatformCarrierCode code = platformCarrierCodeRepository
                .findByCarrier_IdAndPlatform(carrier.getId(), platform)
                .orElseThrow(() -> new IllegalStateException("플랫폼 택배사 코드 미설정: " + platform));

        return code.getDeliveryCompanyCode();
    }
}
