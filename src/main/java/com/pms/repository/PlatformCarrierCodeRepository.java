package com.pms.repository;

import com.pms.domain.PlatformCarrierCode;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformCarrierCodeRepository extends JpaRepository<PlatformCarrierCode, Long> {

    Optional<PlatformCarrierCode> findByCarrier_IdAndPlatform(Long carrierId, String platform);

    /** 플랫폼 코드가 등록된 활성 택배사 — 단건 발송처리 드롭다운(carrier 를 함께 읽으므로 fetch join). */
    @EntityGraph(attributePaths = "carrier")
    List<PlatformCarrierCode> findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc(String platform);

    // 목록: platform 오름차순으로 결정적 조회
    List<PlatformCarrierCode> findByCarrier_IdOrderByPlatformAsc(Long carrierId);

    // 생성 시 중복 검사
    boolean existsByCarrier_IdAndPlatform(Long carrierId, String platform);

    // 수정 시 자기 자신 제외 중복 검사
    boolean existsByCarrier_IdAndPlatformAndIdNot(Long carrierId, String platform, Long id);

    // carrier 삭제 시 자식 cascade 삭제
    void deleteByCarrier_Id(Long carrierId);
}
