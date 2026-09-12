package com.pms.service;

import com.pms.domain.Carrier;
import com.pms.domain.CarrierCatalog;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCarrierCode;
import com.pms.repository.CarrierCatalogRepository;
import com.pms.repository.CarrierRepository;
import com.pms.repository.PlatformCarrierCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final CarrierCatalogRepository carrierCatalogRepository;

    @Override
    public String resolveDeliveryCompanyCode(Platform platform) {
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
    public List<CarrierOption> findOptions(Platform platform) {
        // 택배사 관리에 등록해 둔 코드 — 목록 맨 위로 올린다.
        Map<String, String> registered = registeredNamesByCode(platform);
        List<CarrierCatalog> catalog =
                carrierCatalogRepository.findByPlatformOrderByDisplayOrderAscCodeAsc(platform);

        // 카탈로그가 빈 플랫폼(아직 시드하지 않은 마켓)은 등록된 택배사만이 목록의 전부다
        // (PLAN 2609_37 D9 — 빈 리스트도 정상, 예외 아님).
        if (catalog.isEmpty()) {
            return registered.entrySet().stream()
                    .map(e -> new CarrierOption(e.getKey(), e.getValue(), true))
                    .toList();
        }

        List<CarrierOption> registeredOptions = new ArrayList<>();
        List<CarrierOption> others = new ArrayList<>();
        Set<String> catalogCodes = new LinkedHashSet<>();
        for (CarrierCatalog row : catalog) {
            catalogCodes.add(row.getCode());
            // 표시 이름은 카탈로그를 우선한다 — 로컬 이름이 달라도 코드와 어긋나지 않게 (D13).
            boolean isRegistered = registered.containsKey(row.getCode());
            (isRegistered ? registeredOptions : others)
                    .add(new CarrierOption(row.getCode(), row.getName(), isRegistered));
        }
        // 등록돼 있는데 카탈로그에 없는 코드(옛 코드·오타)는 로컬 이름으로 등록분 끝에 남긴다 —
        // 저장해 둔 값이 드롭다운에서 사라지면 사용자는 자기가 뭘 골랐는지 알 수 없다.
        registered.forEach((code, name) -> {
            if (!catalogCodes.contains(code)) {
                registeredOptions.add(new CarrierOption(code, name, true));
            }
        });

        List<CarrierOption> options = new ArrayList<>(registeredOptions);
        options.addAll(others);
        return options;
    }

    @Override
    public String validateDeliveryCompanyCode(String deliveryCompanyCode, Platform platform) {
        String code = deliveryCompanyCode == null ? "" : deliveryCompanyCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("택배사를 선택하세요");
        }
        // 카탈로그가 있는 플랫폼은 카탈로그가 화이트리스트, 빈 플랫폼은 등록된 코드만 통과 (D9).
        boolean allowed = carrierCatalogRepository.existsByPlatform(platform)
                ? carrierCatalogRepository.existsByPlatformAndCode(platform, code)
                : registeredNamesByCode(platform).containsKey(code);
        if (!allowed) {
            throw new IllegalArgumentException("선택한 택배사를 " + platform + " 에 사용할 수 없습니다: " + code);
        }
        return code;
    }

    /** 그 플랫폼에 코드가 등록된 활성 택배사 — 등록 순서(택배사 id) 유지. */
    private Map<String, String> registeredNamesByCode(Platform platform) {
        Map<String, String> byCode = new LinkedHashMap<>();
        for (PlatformCarrierCode code : platformCarrierCodeRepository
                .findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc(platform)) {
            byCode.putIfAbsent(code.getDeliveryCompanyCode(), code.getCarrier().getName());
        }
        return byCode;
    }
}
