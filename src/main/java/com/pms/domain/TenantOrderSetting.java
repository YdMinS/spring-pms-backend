package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * 테넌트 단위 주문관리 설정 (FEATURE_2609_75 / D12). 테넌트당 1행.
 *
 * <p>행이 없으면 기본값({@code OrderSettingServiceImpl.DEFAULT_RESERVED_SHIPMENT_TIME} = 00:02)을 쓴다.
 */
@Entity
@Table(name = "tenant_order_setting",
        uniqueConstraints = @UniqueConstraint(name = "uq_tenant_order_setting_tenant", columnNames = {"tenant_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class TenantOrderSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 기본 예약 발송 시각 'HH:mm' (KST, D4). */
    @Column(name = "reserved_shipment_time", nullable = false, length = 5)
    private String reservedShipmentTime;
}
