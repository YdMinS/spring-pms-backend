package com.pms.repository;

import com.pms.domain.CarrierCatalog;
import com.pms.domain.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 택배사 코드 카탈로그 조회 (PLAN 2609_37 D1).
 *
 * <p>❌ Controller/다른 Service 에서 직접 호출하지 말 것 —
 * {@link com.pms.service.CarrierCodeService} 를 경유한다(D7: 조회 창구는 하나).
 */
public interface CarrierCatalogRepository extends JpaRepository<CarrierCatalog, Long> {

    List<CarrierCatalog> findByPlatformOrderByDisplayOrderAscCodeAsc(Platform platform);

    boolean existsByPlatform(Platform platform);

    boolean existsByPlatformAndCode(Platform platform, String code);

    /**
     * 이름 → 코드 되찾기 (PLAN 2609_40 D6). 대소문자 무시 · 호출자가 trim 한 이름을 준다.
     *
     * <p>같은 이름이 여러 코드로 존재할 수 있어(롯데 HYUNDAI/LOTTEGLOBAL) 표시 순서가 앞선 행을 쓴다.
     */
    Optional<CarrierCatalog> findFirstByPlatformAndNameIgnoreCaseOrderByDisplayOrderAscCodeAsc(
            Platform platform, String name);
}
