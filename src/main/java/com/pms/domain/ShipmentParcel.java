package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 실물 박스 — <b>우리가 싸는 박스</b> 한 개 (FEATURE_2609_40 / PLAN D1 · D3 · D4).
 *
 * <p>🔴 부모인 {@link OrderShipment} 과 <b>다른 층</b>이다: {@code order_shipment} 는 마켓이 정한 배송 묶음이고,
 * 이 행은 <b>송장 1장이 붙은 실물 상자 1개</b>다. 접수 시트에서 택배수량을 2로 올리면 택배사가 송장 2장을
 * 발급하므로 같은 배송 묶음 아래 이 행이 2개 생긴다.
 *
 * <p>🔴 쿠팡에는 <b>대표 송장 1장만</b> 올라간다(PLAN 2609_40 D8) — 마켓은 배송 묶음 하나에 송장 하나만 받는다.
 * 전송 동작은 이 기능으로 바뀌지 않았다.
 *
 * <p>🔴 유일 제약 {@code (order_shipment_id, invoice_number)}(D7)이 <b>동기화 반복 실행의 유일한 방어선</b>이다.
 * 주문 동기화 백필은 실행할 때마다 같은 송장을 다시 들고 오므로, 이 제약이 없으면 박스가 무한히 늘어난다.
 *
 * <p>🔴 {@code total_parcels} 같은 총 개수 컬럼을 <b>두지 않는다</b> — 같은 배송 묶음의 행 수를 세면 나온다.
 * 두 곳에 적으면 반드시 어긋난다.
 *
 * <p>⚠️ {@code tenant_id} 는 값을 손으로 넣지 않는다 — {@link TenantId} 로 Hibernate 가 채운다
 * ({@link OrderShipment} 선례).
 *
 * @see ShipmentParcelItem 이 박스에 담긴 것(주문 라인 × 물품 × 수량)
 * @see com.pms.service.ShipmentParcelRecorder 이 행을 만드는 유일한 창구
 */
@Entity
@Table(name = "shipment_parcel",
        uniqueConstraints = @UniqueConstraint(name = "uq_shipment_parcel_invoice",
                columnNames = {"order_shipment_id", "invoice_number"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ShipmentParcel extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 부모 = 마켓 배송 묶음. 박스는 언제나 배송 묶음 하나에 속한다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_shipment_id", nullable = false)
    private OrderShipment orderShipment;

    /** 택배사가 발급한 송장번호. 스캔의 열쇠이므로 택배사 코드를 몰라도 이 값만은 저장한다(D6). */
    @Column(name = "invoice_number", nullable = false, length = 60)
    private String invoiceNumber;

    /** 플랫폼 택배사 코드. 이름으로 되찾지 못하면 NULL 이다(D6) — 스캔에는 필요 없다. */
    @Column(name = "carrier_code", length = 50)
    private String carrierCode;

    /** 마켓이 준 택배사 이름 원문(동기화 백필 경로). */
    @Column(name = "carrier_name", length = 100)
    private String carrierName;

    /** 같은 배송 묶음 안에서의 순번 1..N. */
    @Column(name = "parcel_seq", nullable = false)
    private Integer parcelSeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParcelStatus status;

    /** 포장 완료 시각(D3) — 추후 포장 영상 구간을 찾는 열쇠다. */
    @Column(name = "packed_at")
    private LocalDateTime packedAt;

    /** 포장한 사람(D3). */
    @Column(name = "packed_by", length = 100)
    private String packedBy;

    /** 실제로 사용한 상자. 포장 완료 때 채운다(03). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "box_package_id")
    private Package boxPackage;

    /** 판매가 계산에 들어간 상자비 합계(D4) — 절약 집계(2609_41)의 재료. */
    @Column(name = "expected_box_cost", precision = 12, scale = 2)
    private BigDecimal expectedBoxCost;

    /** 실제 상자비(재활용 상자는 0). */
    @Column(name = "actual_box_cost", precision = 12, scale = 2)
    private BigDecimal actualBoxCost;

    /** 판매가 계산에 들어간 택배비 합계(D4, 2차 재료). */
    @Column(name = "expected_delivery_cost", precision = 12, scale = 2)
    private BigDecimal expectedDeliveryCost;
}
