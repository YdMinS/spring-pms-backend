package com.pms.repository;

import com.pms.domain.ShipmentParcel;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
