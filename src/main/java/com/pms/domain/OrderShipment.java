package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * 배송 묶음 — oclyx 소유 중립 core (FEATURE_2609_26 / PLAN D2·D8).
 *
 * <p>쿠팡 shipmentBox 의 중립 이름이다. 박스는 쿠팡 전용 개념이 아니라 <b>배송 묶음</b>이며
 * 송장·배송비·발송처리가 전부 이 단위로 움직인다.
 *
 * <p>🔴 배송비·도서산간비는 <b>1행 1값</b>이다 — 라인에 복제하면 박스에 2줄 이상일 때 SUM 이 중복 합산된다.
 *
 * <p>⚠️ 송장번호·택배사 컬럼은 <b>없다</b> — 로컬에 저장하지 않는다(2609_11 D9 유지).
 *
 * <p>⚠️ {@code externalShipmentId} 가 blank 인 라인은 이 행을 만들지 않는다
 * ({@link com.pms.service.coupang.OrderUpserter} 참고) — 빈 문자열을 UNIQUE 에 넣으면 주문당 1행으로 뭉친다.
 *
 * <p>⚠️ ddl-auto=validate(운영) → 아래 @Column 정의는 실제 order_shipment DDL(changeset 066)과 일치해야 한다.
 * {@code tracking_available} 은 MySQL 에서 BIT(1) 이다(066 후속 changeset).
 */
@Entity
@Table(name = "order_shipment",
        uniqueConstraints = @UniqueConstraint(name = "uq_order_shipment",
                columnNames = {"order_id", "external_shipment_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OrderShipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (PLAN D25) — 자식 테이블도 tenant 컬럼을 갖는다(058·064·062 관례).
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 플랫폼 배송묶음 식별자(쿠팡 shipmentBoxId). blank 면 행 자체를 만들지 않는다. */
    @Column(name = "external_shipment_id", nullable = false, length = 100)
    private String externalShipmentId;

    /** 배송비(쿠팡 shippingPrice). 과거 데이터는 복구 불가라 null 이다(PLAN D13). */
    @Column(name = "shipping_fee", precision = 12, scale = 2)
    private BigDecimal shippingFee;

    /** 도서산간 추가배송비(쿠팡 remotePrice). 과거 데이터는 null. */
    @Column(name = "remote_fee", precision = 12, scale = 2)
    private BigDecimal remoteFee;

    /**
     * 배송 추적 가능 여부. 쿠팡 NONE_TRACKING(업체 직접배송)이면 false.
     *
     * <p>추적 가능 여부는 상태가 아니라 배송 묶음의 속성이다(PLAN D5) — 상태는 {@code SHIPPED} 로 접힌다.
     */
    @Column(name = "tracking_available")
    private Boolean trackingAvailable;
}
