package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 주문 라인 (플랫폼 공통, 쿠팡 데이터의 거울).
 *
 * 동기화({@link com.pms.service.coupang.CoupangOrderSyncService})가 이 테이블의 쓰기 주체다 — 우리 작업 상태는
 * 여기에 두지 않는다(별도 product_purchase). 예외 하나: 발송처리({@link com.pms.service.ShipmentConfirmService})가
 * 송장업로드에 성공한 박스의 {@code status} 만 {@code DEPARTURE} 로 갱신한다(PLAN 2609_07 D4).
 * ordersheets 응답의 shipmentBox×orderItem 1줄이 1행이며,
 * UNIQUE(marketplace_account_id, external_box_id, external_order_id, external_item_id) 로 멱등 upsert 된다.
 *
 * 파생: 발주가능수량 = order_count − (cancel_count + hold_count). {@link #purchasableQty()} 참고.
 *
 * 고객 <b>이름</b>(주문자·수취인)만 저장한다 — 연락처·주소·배송메시지는 여전히 미저장(개인정보 최소화).
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

    // Tenant dimension (changeset 002). Hibernate auto-sets this on INSERT and auto-filters
    // SELECTs from TenantIdentifierResolver — do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

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

    @Column(name = "orderer_name", length = 100)
    private String ordererName;              // 쿠팡 shipmentBox.orderer.name (박스 레벨 → 라인마다 복제)

    @Column(name = "receiver_name", length = 100)
    private String receiverName;             // 쿠팡 shipmentBox.receiver.name

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw")
    private String raw;                      // 원본 orderItem JSON (플랫폼별 특이 필드 흡수)

    /** 발주가능수량 = orderCount − (cancelCount + holdCount), 음수면 0. */
    public int purchasableQty() {
        return Math.max(0, orderCount - (cancelCount + holdCount));
    }

    /**
     * 전량 취소 여부 = 확정취소+환불대기(cancelCount+holdCount) 가 주문수량 이상.
     *
     * 판매자 취소는 status 를 바꾸지 않고 취소수량만 반영되므로(returnRequests 동기화는 status 미변경,
     * 또 전량취소된 주문은 INSTRUCT 목록에서 빠져 status 가 얼어붙는다), 취소수량으로 취소를 판정한다.
     */
    public boolean isFullyCancelled() {
        return orderCount != null && orderCount > 0 && (cancelCount + holdCount) >= orderCount;
    }

    /**
     * 화면 표시용 유효 상태 — 전량 취소면 {@code "CANCELLED"}, 아니면 원본 status.
     * 원본 status(쿠팡 코드)는 별도로 유지하고, 이 값이 "취소했는데 상품준비중" 오표시를 막는다.
     */
    public String effectiveStatus() {
        return isFullyCancelled() ? "CANCELLED" : status;
    }
}
