package com.pms.repository;

import com.pms.domain.ReservedItemResult;
import com.pms.domain.ReservedShipmentItem;
import com.pms.domain.ReservedShipmentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 예약 결과 리포지토리 (FEATURE_2609_75). {@code @TenantId} 자동 필터. */
public interface ReservedShipmentItemRepository extends JpaRepository<ReservedShipmentItem, Long> {

    /** 이 배송 묶음들 중 실행 중인 예약에 든 것이 있는가 — 「처리 중」 차단(D18). */
    boolean existsByOrderShipment_IdInAndReservedShipment_Status(Collection<Long> orderShipmentIds,
                                                                  ReservedShipmentStatus status);

    /** 배송 묶음들의 결과 중 지정 결과인 것 — 예약 해제·송장 교체·보관 송장(STORED) 조회. 예약을 함께 읽는다(STORED 는 null). */
    @EntityGraph(attributePaths = {"reservedShipment"})
    List<ReservedShipmentItem> findByOrderShipment_IdInAndResultIn(Collection<Long> orderShipmentIds,
                                                                   Collection<ReservedItemResult> results);

    /** 예약 1건의 결과 전부. */
    List<ReservedShipmentItem> findByReservedShipment_Id(Long reservedShipmentId);

    /** 예약 1건의 지정 결과들 — 실행 대상. 배송 묶음을 함께 읽는다(트랜잭션 밖에서 박스 id 를 쓴다). */
    @EntityGraph(attributePaths = {"orderShipment"})
    List<ReservedShipmentItem> findByReservedShipment_IdAndResultIn(Long reservedShipmentId,
                                                                    Collection<ReservedItemResult> results);

    /** 여러 예약의 결과 전부 — 목록 응답(N+1 금지). */
    @EntityGraph(attributePaths = {"orderShipment", "reservedShipment"})
    List<ReservedShipmentItem> findByReservedShipment_IdIn(Collection<Long> reservedShipmentIds);

    /** 주문 1건의 결과 전부(최근 것부터) — 주문 상세의 예약 발송 기록(E13, D30). */
    @EntityGraph(attributePaths = {"orderShipment", "reservedShipment"})
    List<ReservedShipmentItem> findByExternalOrderIdOrderByIdDesc(String externalOrderId);

    /**
     * 테넌트 범위 단건 조회(주문 행 작업 E8·E9). PK {@code findById()} 는 테넌트 필터가 안 걸린다.
     * 트랜잭션 밖(E9)에서도 배송 묶음·예약을 읽으므로 둘 다 함께 로딩한다.
     */
    @EntityGraph(attributePaths = {"orderShipment", "reservedShipment"})
    @Query("select i from ReservedShipmentItem i where i.id = :id")
    Optional<ReservedShipmentItem> findScopedById(@Param("id") Long id);
}
