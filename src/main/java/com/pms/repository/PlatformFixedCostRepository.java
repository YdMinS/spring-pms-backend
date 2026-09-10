package com.pms.repository;

import com.pms.domain.Platform;
import com.pms.domain.PlatformFixedCost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 고정비 카탈로그 (FEATURE_2609_33 / PLAN 2609_33 D1).
 *
 * <p>테넌트 스코프는 {@code @TenantId} 가 자동으로 건다 — 수동 tenant 조건을 넣지 말 것.
 */
public interface PlatformFixedCostRepository extends JpaRepository<PlatformFixedCost, Long> {

    /** 채널 설정 화면이 고를 수 있는 항목 — 끈 항목은 새로 연결하지 못한다. */
    List<PlatformFixedCost> findByPlatformAndActiveTrue(Platform platform);

    /** 카탈로그 목록(관리 화면). 비활성 항목도 보여야 다시 켤 수 있다. */
    List<PlatformFixedCost> findAllByOrderByPlatformAscNameAsc();

    boolean existsByPlatformAndName(Platform platform, String name);

    boolean existsByPlatformAndNameAndIdNot(Platform platform, String name, Long id);
}
