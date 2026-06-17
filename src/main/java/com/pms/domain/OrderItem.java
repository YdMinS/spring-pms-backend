package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 주문 라인 (플랫폼 공통, 쿠팡 데이터의 거울).
 *
 * 동기화({@link com.pms.service.coupang.CoupangOrderSyncService})만 이 테이블에 쓴다 — 우리 작업 상태는
 * 여기에 두지 않는다(별도 product_purchase). ordersheets 응답의 shipmentBox×orderItem 1줄이 1행이며,
 * UNIQUE(marketplace_account_id, external_box_id, external_order_id, external_item_id) 로 멱등 upsert 된다.
 *
 * 파생: 발주가능수량 = order_count − (cancel_count + hold_count). {@link #purchasableQty()} 참고.
 *
 * ⚠️ ddl-auto=validate(운영) → @Column 정의가 실제 order_item DDL 과 일치해야 한다.
 *    raw 는 운영(MySQL) JSON 컬럼(JDBC LONGVARCHAR)이다. @Lob 은 CLOB 을 기대해 검증에 실패하므로
 *    @JdbcTypeCode(LONGVARCHAR) 로 매핑해 json 컬럼과 일치시킨다(H2 create-drop 호환).
 */
@Entity
@Table(name = "order_item",
        uniqueConstraints = @UniqueConstraint(name = "uq_order_item",
                columnNames = {"marketplace_account_id", "external_box_id", "external_order_id", "external_item_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    @Column(nullable = false, length = 50)
    private String platform;                 // "COUPANG"

    @Column(name = "external_order_id", nullable = false, length = 100)
    private String externalOrderId;          // 쿠팡 orderId

    @Column(name = "external_box_id", length = 100)
    private String externalBoxId;            // 쿠팡 shipmentBoxId (없으면 null)

    @Column(name = "external_item_id", nullable = false, length = 100)
    private String externalItemId;           // 쿠팡 vendorItemId = 옵션ID (옵션 매칭키)

    @Column(name = "order_count", nullable = false)
    private Integer orderCount;              // shippingCount

    @Column(name = "cancel_count", nullable = false)
    private Integer cancelCount;             // 취소확정 수량 (기본 0)

    @Column(name = "hold_count", nullable = false)
    private Integer holdCount;               // holdCountForCancel = 환불대기 수량 (기본 0)

    @Column(nullable = false, length = 30)
    private String status;                   // ACCEPT 등

    @Column(name = "paid_at")
    private LocalDateTime paidAt;            // 참고/정렬용 (필터 아님)

    @Column(name = "item_name", length = 500)
    private String itemName;                 // 표시용

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw")
    private String raw;                      // 원본 orderItem JSON (플랫폼별 특이 필드 흡수)

    /** 발주가능수량 = orderCount − (cancelCount + holdCount), 음수면 0. */
    public int purchasableQty() {
        return Math.max(0, orderCount - (cancelCount + holdCount));
    }
}
