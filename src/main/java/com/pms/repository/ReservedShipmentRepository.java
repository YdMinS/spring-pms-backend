package com.pms.repository;

import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 예약 발송 리포지토리 (FEATURE_2609_75). {@code @TenantId} 자동 필터 — 수동 테넌트 조건 금지.
 * PK {@code findById()} 는 테넌트 필터가 안 걸리므로 화면 경로의 단건 조회는 {@link #findScopedById} 를 쓴다.
 */
public interface ReservedShipmentRepository extends JpaRepository<ReservedShipment, Long> {

    /** 테넌트 범위 단건 조회. 다른 테넌트 id 면 empty. */
    @Query("select r from ReservedShipment r where r.id = :id")
    Optional<ReservedShipment> findScopedById(@Param("id") Long id);

    /** 기한이 된 예약(스케줄). 오래된 것부터. */
    List<ReservedShipment> findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(ReservedShipmentStatus status,
                                                                                    LocalDateTime now);

    /** 기동 복구용 — 실행 중에 서버가 꺼진 예약. */
    List<ReservedShipment> findByStatus(ReservedShipmentStatus status);

    /**
     * 목록(E6) 후보 — 끝나지 않은 예약 전부 + executeAt ≥ since 인 예약 + lastRunAt ≥ since 인 예약. 실행 시각 늦은 것부터.
     * 행(주문) 단위 거르기는 서비스가 한다(D30).
     */
    @Query("SELECT r FROM ReservedShipment r WHERE r.status IN :open OR r.executeAt >= :since OR r.lastRunAt >= :since "
            + "ORDER BY r.executeAt DESC, r.id DESC")
    List<ReservedShipment> findForList(@Param("open") Collection<ReservedShipmentStatus> open,
                                       @Param("since") LocalDateTime since);

    /**
     * 상태 전이 — {@code from} 일 때만 {@code to} 로. 반환 1 = 이 호출이 가져갔다, 0 = 이미 다른 실행이 가져갔다.
     * 서버 1대 전제의 동시 실행 방지다(discussion §4 — 다중 서버는 별건).
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ReservedShipment r SET r.status = :to WHERE r.id = :id AND r.status = :from")
    int transition(@Param("id") Long id, @Param("from") ReservedShipmentStatus from,
                   @Param("to") ReservedShipmentStatus to);
}
