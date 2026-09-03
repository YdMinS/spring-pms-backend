package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.domain.PlatformCarrierCode;
import com.pms.repository.CarrierRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final String PLATFORM_COUPANG = "COUPANG";

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

    @Override
    public List<CarrierOption> findOptions(String platform) {
        // 택배사 관리에 등록해 둔 코드 — 쿠팡이면 목록 맨 위로, 그 외 플랫폼이면 이것이 목록의 전부다.
        Map<String, String> registered = registeredNamesByCode(platform);
        if (!PLATFORM_COUPANG.equals(platform)) {
            return registered.entrySet().stream()
                    .map(e -> new CarrierOption(e.getKey(), e.getValue(), true))
                    .toList();
        }

        List<CarrierOption> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : registered.entrySet()) {
            // 표시 이름은 쿠팡 표를 우선한다 — 로컬 이름이 달라도 코드와 어긋나지 않게.
            options.add(new CarrierOption(entry.getKey(),
                    CoupangCourierCodes.nameOf(entry.getKey()), true));
        }
        CoupangCourierCodes.all().forEach((code, name) -> {
            if (!registered.containsKey(code)) {
                options.add(new CarrierOption(code, name, false));
            }
        });
        return options;
    }

    @Override
    public String validateDeliveryCompanyCode(String deliveryCompanyCode, String platform) {
        String code = deliveryCompanyCode == null ? "" : deliveryCompanyCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("택배사를 선택하세요");
        }
        boolean allowed = PLATFORM_COUPANG.equals(platform)
                ? CoupangCourierCodes.contains(code)
                : registeredNamesByCode(platform).containsKey(code);
        if (!allowed) {
            throw new IllegalArgumentException("선택한 택배사를 " + platform + " 에 사용할 수 없습니다: " + code);
        }
        return code;
    }

    /** 그 플랫폼에 코드가 등록된 활성 택배사 — 등록 순서(택배사 id) 유지. */
    private Map<String, String> registeredNamesByCode(String platform) {
        Map<String, String> byCode = new LinkedHashMap<>();
        for (PlatformCarrierCode code : platformCarrierCodeRepository
                .findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc(platform)) {
            byCode.putIfAbsent(code.getDeliveryCompanyCode(), code.getCarrier().getName());
        }
        return byCode;
    }
}
