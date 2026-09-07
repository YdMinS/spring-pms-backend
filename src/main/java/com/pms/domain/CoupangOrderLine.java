package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * 쿠팡 주문 라인 extension — {@link OrderLine} 의 거울 (FEATURE_2609_26 / PLAN D3·D11).
 *
 * <p>🔴 <b>라인의 자연키를 이 테이블이 소유한다</b>: UNIQUE(marketplace_account_id, shipment_box_id,
 * order_id_raw, vendor_item_id). 쿠팡은 4키, 네이버는 {@code productOrderId} 1키라 한 테이블에 담으면
 * 네이버 행의 박스 컬럼이 NULL 이 되는데 MySQL 은 NULL 중복을 허용한다 → 유니크가 풀려 멱등 upsert 가 무너진다.
 *
 * <p>동기화가 이 테이블의 쓰기 주체다(거울) — 우리 소유 값(금액 스냅샷·작업 상태)은 여기 두지 않는다.
 *
 * <p>⚠️ ddl-auto=validate(운영) → {@code raw} 는 MySQL JSON 컬럼(JDBC LONGVARCHAR)이다.
 * {@code @Lob} 은 CLOB 을 기대해 검증에 실패하므로 {@code @JdbcTypeCode(LONGVARCHAR)} 로 매핑한다
 * (H2 create-drop 호환).
 */
@Entity
@Table(name = "coupang_order_line",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_coupang_order_line",
                        columnNames = {"marketplace_account_id", "shipment_box_id", "order_id_raw", "vendor_item_id"}),
                @UniqueConstraint(name = "uq_coupang_order_line_line", columnNames = "order_line_id")
        })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CoupangOrderLine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (PLAN D25) — 자체 조회 표면(4키·3키 finder)이 있으므로 자식이어도 @TenantId 다.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine orderLine;

    /** 자연키의 일부라 core 를 거치지 않고 바로 필터할 수 있어야 한다(부모 경유 조인 회피). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    /**
     * 쿠팡 shipmentBoxId 원문. 값이 없으면 <b>빈 문자열</b>이다(null 아님) — 자연키의 일부라
     * null 로 바꾸면 MySQL 이 NULL 중복을 허용해 UNIQUE 가 풀린다.
     * 배송 묶음({@link OrderShipment}) 행은 이 값이 blank 면 만들지 않는다.
     */
    @Column(name = "shipment_box_id", length = 100)
    private String shipmentBoxId;

    /** 쿠팡 orderId 원문. core 의 {@code orders.external_order_id} 와 같은 값이지만 자연키라 여기도 둔다. */
    @Column(name = "order_id_raw", nullable = false, length = 100)
    private String orderIdRaw;

    /** 쿠팡 vendorItemId = 옵션 ID (옵션 매칭키). */
    @Column(name = "vendor_item_id", nullable = false, length = 100)
    private String vendorItemId;

    /** 쿠팡 상태 원문(ACCEPT 등) — 번역·요약 금지. 정규화 값은 {@link OrderLine#getStatus()}. */
    @Column(name = "platform_status", length = 30)
    private String platformStatus;

    /** 원본 orderItem JSON (플랫폼별 특이 필드 흡수). 동기화마다 덮어쓴다(거울). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw")
    private String raw;
}
