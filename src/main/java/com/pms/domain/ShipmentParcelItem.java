package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * 실물 박스에 담긴 것 — (주문 라인 × 물품 × 수량) (FEATURE_2609_40 / PLAN D2).
 *
 * <p>🔴 항목마다 {@code order_line_id} 를 가진다: 출고·원가가 주문에 매달려야 하고,
 * <b>나중에 여러 주문을 한 박스에 담게 돼도 모델을 다시 만들 필요가 없다</b>. 박스를 주문에 직접 매달면
 * 그때 전부 갈아엎어야 한다.
 *
 * <p>⚠️ 이 조각(01)은 <b>테이블과 엔티티만</b> 만든다 — 행을 쓰는 것은 포장 콘솔(03)이다.
 *
 * @see ShipmentParcel 부모 박스
 */
@Entity
@Table(name = "shipment_parcel_item")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ShipmentParcelItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_parcel_id", nullable = false)
    private ShipmentParcel shipmentParcel;

    /** 🔴 어느 주문의 것인지 — 합포장 대비(D2). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine orderLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private Integer quantity;
}
