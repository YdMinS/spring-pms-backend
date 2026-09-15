package com.pms.repository;

import com.pms.domain.Platform;
import com.pms.domain.PlatformCarrierCode;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlatformCarrierCodeRepository extends JpaRepository<PlatformCarrierCode, Long> {

    Optional<PlatformCarrierCode> findByCarrier_IdAndPlatform(Long carrierId, Platform platform);

    /**
     * 전 플랫폼 코드 + 택배사 — 박스에 저장된 <b>플랫폼 택배사 코드</b>를 내부 택배사로 되돌리는 표
     * (FEATURE_2609_41 / PLAN 2609_41 S15).
     *
     * <p>박스에는 쿠팡 {@code "CJGLS"} 같은 플랫폼 코드만 있고 요율({@code carrier_rate})은 내부
     * {@link com.pms.domain.Carrier} 에 달려 있다 — 둘을 잇는 유일한 표가 이것이다. lookup 테이블이라
     * 통째로 읽고 메모리에서 찾는다(박스마다 조회하면 N+1).
     */
    @Query("select c from PlatformCarrierCode c join fetch c.carrier")
    List<PlatformCarrierCode> findAllWithCarrier();

    /** 플랫폼 코드가 등록된 활성 택배사 — 단건 발송처리 드롭다운(carrier 를 함께 읽으므로 fetch join). */
    @EntityGraph(attributePaths = "carrier")
    List<PlatformCarrierCode> findByPlatformAndCarrier_IsActiveTrueOrderByCarrier_IdAsc(Platform platform);

    // 목록: platform 오름차순으로 결정적 조회
    List<PlatformCarrierCode> findByCarrier_IdOrderByPlatformAsc(Long carrierId);

    // 생성 시 중복 검사
    boolean existsByCarrier_IdAndPlatform(Long carrierId, Platform platform);

    // 수정 시 자기 자신 제외 중복 검사
    boolean existsByCarrier_IdAndPlatformAndIdNot(Long carrierId, Platform platform, Long id);

    // carrier 삭제 시 자식 cascade 삭제
    void deleteByCarrier_Id(Long carrierId);
}
