package com.pms.repository;

import com.pms.domain.InternalShipmentStage;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 배송 묶음 리포지토리 (FEATURE_2609_26 / PLAN D2). 부모(주문) 경유 finder 만 노출한다. */
public interface OrderShipmentRepository extends JpaRepository<OrderShipment, Long> {

    /** UNIQUE 키(주문 + 배송묶음 식별자)로 조회 — 적재 upsert 의 멱등성 키. */
    Optional<OrderShipment> findByOrder_IdAndExternalShipmentId(Long orderId, String externalShipmentId);

    /**
     * 내부 단계를 바꾸는 <b>유일한</b> 쓰기 경로 ① (FEATURE_2609_75 / D1). 엔티티 칸이 {@code updatable = false} 라
     * 엔티티 저장으로는 바뀌지 않는다. ids 는 테넌트 필터가 걸린 조회에서 얻은 값만 넘긴다(native 라 필터가 없다).
     *
     * @param stage {@code InternalShipmentStage.name()}
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE order_shipment SET internal_stage = :stage WHERE id IN (:ids)", nativeQuery = true)
    int setInternalStage(@Param("ids") Collection<Long> ids, @Param("stage") String stage);

    /** 내부 단계를 바꾸는 <b>유일한</b> 쓰기 경로 ② — 없음(NULL)으로. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE order_shipment SET internal_stage = NULL WHERE id IN (:ids)", nativeQuery = true)
    int clearInternalStage(@Param("ids") Collection<Long> ids);

    /**
     * 계정의 지정 단계 배송 묶음 중 저장 status 가 {@code lineStatus} 인 라인이 있는 것 — 내부 접수시트
     * (FEATURE_2609_75 / D26 · D29). 호출자는 {@code OrderStatus.PAID} 만 넘긴다. 주문(결제일·주문번호)을 함께 읽는다.
     */
    @EntityGraph(attributePaths = {"order"})
    @Query("SELECT s FROM OrderShipment s WHERE s.internalStage = :stage AND s.order.marketplaceAccount.id = :accountId "
            + "AND EXISTS (SELECT l.id FROM OrderLine l WHERE l.orderShipment = s AND l.status = :lineStatus)")
    List<OrderShipment> findByInternalStageAndAccountWithLineStatus(@Param("stage") InternalShipmentStage stage,
                                                                    @Param("accountId") Long accountId,
                                                                    @Param("lineStatus") OrderStatus lineStatus);
}
