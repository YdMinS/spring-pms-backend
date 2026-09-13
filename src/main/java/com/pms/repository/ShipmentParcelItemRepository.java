package com.pms.repository;

import com.pms.domain.ShipmentParcelItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 박스 내용물 리포지토리 (FEATURE_2609_40 / PLAN D2).
 *
 * <p>⚠️ 행을 쓰는 것은 포장 콘솔(03)이다 — 이 조각은 모델만 만든다.
 */
public interface ShipmentParcelItemRepository extends JpaRepository<ShipmentParcelItem, Long> {

    List<ShipmentParcelItem> findByShipmentParcel_Id(Long shipmentParcelId);
}
