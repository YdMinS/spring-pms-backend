package com.pms.repository;

import com.pms.domain.ParcelStatus;
import com.pms.domain.ShipmentParcel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 실물 박스 리포지토리 (FEATURE_2609_40 / PLAN D1 · D7).
 *
 * <p>❌ Controller/다른 Service 에서 {@code save} 를 직접 부르지 말 것 — 박스 생성은
 * {@link com.pms.service.ShipmentParcelRecorder} 하나가 소유한다(유일 제약 판정이 거기 있다).
 */
public interface ShipmentParcelRepository extends JpaRepository<ShipmentParcel, Long> {

    /** UNIQUE 키(배송묶음 + 송장번호) — 발송처리·동기화 백필의 멱등성 키(D7). */
    Optional<ShipmentParcel> findByOrderShipment_IdAndInvoiceNumber(Long orderShipmentId, String invoiceNumber);

    /** 그 배송 묶음의 현재 박스 수 — 다음 {@code parcel_seq} 는 이 값 + 1 이다. */
    long countByOrderShipment_Id(Long orderShipmentId);

    List<ShipmentParcel> findByOrderShipment_IdOrderByParcelSeqAsc(Long orderShipmentId);

    // ── 포장 콘솔 (FEATURE_2609_40 / 03) ──────────────────────────────────────

    /**
     * 송장번호 하나로 박스를 찾는다 — 스캔의 입구(PLAN 2609_40 D9).
     *
     * <p>⚠️ 배송 묶음을 모르는 조회다: 작업자가 들고 있는 것은 송장 한 장뿐이라
     * {@link #findByOrderShipment_IdAndInvoiceNumber} 를 쓸 수 없다. 유일 제약은 (배송묶음, 송장번호)라
     * 이론적으로는 서로 다른 묶음이 같은 송장번호를 가질 수 있어 목록으로 받고 가장 오래된 것을 쓴다 —
     * 택배사가 송장번호를 재사용하지 않는 한 언제나 1건이다.
     */
    List<ShipmentParcel> findByInvoiceNumberOrderByIdAsc(String invoiceNumber);

    /** 배송 묶음 여러 개의 박스를 한 번에 — 작업 목록이 묶음별 총 박스 수를 셀 때 쓴다(N+1 금지). */
    List<ShipmentParcel> findByOrderShipment_IdIn(Collection<Long> orderShipmentIds);

    /**
     * 상태별 박스 + 판매자 필터 — 작업 대상 목록(D16).
     *
     * <p>🔴 <b>잔량 0 걸러내기는 여기서 하지 않는다</b>(D31). 잔량은 {@code STOCK_OUT} 합계라
     * SQL 로 재현하는 순간 두 번째 계산기가 생긴다(D28) — 거르는 것은 서비스다.
     *
     * <p>⚠️ 판매자는 {@code 박스 → 배송묶음 → 주문 → 계정 → 판매자} 로 해석한다(출고 화면과 같은 축).
     */
    @Query("""
            select p from ShipmentParcel p
              join p.orderShipment s
              join s.order o
              join o.marketplaceAccount a
              join a.seller sel
             where p.status = :status
               and (:sellerId is null or sel.id = :sellerId)
             order by p.id asc
            """)
    List<ShipmentParcel> findByStatusAndSeller(@Param("status") ParcelStatus status,
                                               @Param("sellerId") Long sellerId);

    // ── 절약 집계 (FEATURE_2609_41 / 01) ──────────────────────────────────────

    /**
     * 기간 안에 <b>포장 완료된</b> 박스 — 절약 집계의 원천(PLAN 2609_41 S7 · S14).
     *
     * <p>🔴 기준일은 {@code packed_at} 이다 — 매출 화면이 쓰는 <b>주문일</b>({@code order.orderedAt})이
     * 아니다. 절약은 포장이라는 행위에서 발생하므로 주문일 기준 달에 붙이면 "이번 달에 아낀 돈"에 답할 수 없다.
     * 상한은 매출 쿼리와 같게 <b>배타</b>(다음 날 00:00)다.
     *
     * <p>🔴 {@code nativeQuery} 로 바꾸지 말 것: {@code ShipmentParcel}·{@code OrderShipment} 는
     * {@code @TenantId} 라 JPQL 에서만 테넌트 필터가 자동으로 붙는다(수동 tenant 조건도 넣지 않는다).
     *
     * <p>⚠️ 판매자는 {@code 박스 → 배송묶음 → 주문 → 계정 → 판매자} 로 내려간다({@link #findByStatusAndSeller}
     * 와 같은 축). 채널 옵션을 경유하면 채널 전용·미매핑 옵션의 박스가 통째로 빠진다.
     *
     * <p>⚠️ {@code join fetch} 는 N+1 방지다 — 배송묶음(배송비 판정, S3)·주문(플랫폼, S15)·상자(치수·종류)를
     * 박스마다 다시 읽지 않는다.
     */
    @Query("""
            select p from ShipmentParcel p
              join fetch p.orderShipment s
              join fetch s.order o
              left join fetch p.boxPackage box
              join o.marketplaceAccount a
              join a.seller sel
             where p.status = :status
               and p.packedAt >= :from and p.packedAt < :toExclusive
               and (:sellerId is null or sel.id = :sellerId)
             order by p.id asc
            """)
    List<ShipmentParcel> findPackedBetween(@Param("status") ParcelStatus status,
                                           @Param("from") LocalDateTime from,
                                           @Param("toExclusive") LocalDateTime toExclusive,
                                           @Param("sellerId") Long sellerId);
}
