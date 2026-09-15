package com.pms.repository;

import com.pms.domain.ShipmentParcelItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 박스 내용물 리포지토리 (FEATURE_2609_40 / PLAN D2).
 *
 * <p>⚠️ 행을 쓰는 것은 포장 콘솔(03)이다 — 이 조각은 모델만 만든다.
 */
public interface ShipmentParcelItemRepository extends JpaRepository<ShipmentParcelItem, Long> {

    List<ShipmentParcelItem> findByShipmentParcel_Id(Long shipmentParcelId);

    /**
     * 박스 여러 개의 내용물을 <b>한 번에</b> — 절약 집계의 배분 재료(FEATURE_2609_41 / PLAN 2609_41 S5).
     *
     * <p>🔴 {@code join fetch} 사슬이 N+1 방어선이다: 배분 축이 채널 옵션이고 응답 키는 마스터 옵션이라
     * (라인 → 채널 옵션 → 셀 → 마스터 옵션 → 마스터 상품)을 전부 타게 된다. 박스마다 이 경로를 다시 읽으면
     * 박스 수에 비례한 쿼리가 나간다.
     *
     * <p>⚠️ {@code left join} 인 이유: 채널 옵션이 없는 라인도 <b>버리지 않는다</b> — 그 박스는 집계에서
     * 빠지되 「근거 없음」으로 세어야 하고, 조용히 사라지면 합계가 왜 작은지 아무도 모른다(S14).
     */
    @Query("""
            select i from ShipmentParcelItem i
              join fetch i.shipmentParcel p
              join fetch i.orderLine l
              left join fetch l.productListingOption plo
              left join fetch plo.productListing cell
              left join fetch plo.masterProductOption mpo
              left join fetch mpo.masterProduct mp
             where p.id in :parcelIds
             order by i.id asc
            """)
    List<ShipmentParcelItem> findWithLineByParcelIdIn(@Param("parcelIds") Collection<Long> parcelIds);
}
