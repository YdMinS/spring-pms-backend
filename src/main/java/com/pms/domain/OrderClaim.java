package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * 클레임(반품·교환) 라인 (FEATURE_2609_18 / PLAN §3).
 *
 * {@link OrderItem} 과 같은 입도 = 라인 단위 1행이며, 한 라인에 클레임이 여러 건 붙을 수 있다
 * (부분 반품 2회, 반품 후 교환). UNIQUE(marketplace_account_id, claim_type, external_claim_id,
 * external_item_id) 로 멱등 upsert 된다 — {@code claim_type} 이 키에 있어야 반품 {@code receiptId} 와
 * 교환 {@code exchangeId} 가 겹쳐도 서로를 덮어쓰지 않는다(D24).
 *
 * <p>쓰기 주체는 동기화뿐이다 — 반품은 {@link com.pms.service.coupang.CoupangReturnSyncServiceImpl}
 * 이 이미 도는 returnRequests 5배치 응답을 재사용해 적재한다(D15, 쿠팡 호출 0건 추가).
 *
 * <p>참조는 <b>claim → order_item 단방향</b>이다(D14) — {@code OrderItem} 에 역참조 컬렉션을 두면
 * 주문 조회마다 딸려온다. 주문 매칭에 실패해도 claim 은 저장하고 {@code orderItem} 만 null 로 둔다(D12).
 *
 * <p>⚠️ PII 는 <b>이름만</b> 저장한다(D19) — 연락처·회수 주소·raw 응답 컬럼을 추가하지 말 것.
 */
@Entity
@Table(name = "order_claim",
        uniqueConstraints = @UniqueConstraint(name = "uq_orderclaim_account_type_claim_item",
                columnNames = {"marketplace_account_id", "claim_type", "external_claim_id", "external_item_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OrderClaim extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    @Column(nullable = false, length = 50)
    private String platform;                        // "COUPANG" (order_item 관례를 따라 둔다)

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 20)
    private ClaimType claimType;

    @Column(name = "external_claim_id", nullable = false, length = 100)
    private String externalClaimId;                 // 쿠팡 receiptId (교환은 exchangeId)

    @Column(name = "external_order_id", nullable = false, length = 100)
    private String externalOrderId;                 // 쿠팡 orderId

    @Column(name = "external_box_id", length = 100)
    private String externalBoxId;                   // 쿠팡 shipmentBoxId (목록 응답에 없을 수 있다 — D22)

    @Column(name = "external_item_id", nullable = false, length = 100)
    private String externalItemId;                  // 쿠팡 vendorItemId = 옵션ID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;                    // 매칭 실패 시 null (D12·D22)

    @Column(name = "order_item_match_attempts", nullable = false)
    private Integer orderItemMatchAttempts;         // 단건 주문조회 백필 시도 횟수 (04 가 증가시킨다)

    @Column(name = "item_name", length = 500)
    private String itemName;                        // vendorItemName — 주문 미연결 시 화면 공백 방지

    @Column(nullable = false)
    private Integer quantity;                       // cancelCount

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status;                     // 정규화 상태 (§3.1)

    @Column(name = "platform_status", nullable = false, length = 30)
    private String platformStatus;                  // 원문 (RU / UC / CC / PR ...)

    /**
     * 교환 회수상태 원문 (BeforeDirection / CompleteCollect ...) — 교환 액션의 가능 조건이다
     * (FEATURE_2609_21 / 05). 컬럼 이름은 플랫폼 중립, 값은 원문 그대로다({@code platformStatus} 와
     * 같은 자세) — 우리 enum 으로 정규화하면 액션 판정이 축약된 값 위에서 돌게 된다(D2).
     *
     * <p>⚠️ null 이면 <b>액션을 열지 않는다</b>(D3). 비어 있는 것과 조건을 만족하는 것은 다르다 —
     * 기존 행은 비어 있고 다음 동기화가 채운다(백필 없음).
     */
    @Column(name = "collect_status", length = 30)
    private String collectStatus;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "reason_text", length = 1000)
    private String reasonText;

    @Column(name = "fault_type", length = 50)
    private String faultType;                       // 귀책 (faultByType)

    @Column(name = "return_shipping_charge")
    private Integer returnShippingCharge;           // 반품 전용

    @Column(name = "collect_invoice_no", length = 100)
    private String collectInvoiceNo;                // 회수 송장

    @Column(name = "collect_carrier_code", length = 50)
    private String collectCarrierCode;

    @Column(name = "reship_invoice_no", length = 100)
    private String reshipInvoiceNo;                 // 교환 전용 (06 이 채운다)

    @Column(name = "reship_carrier_code", length = 50)
    private String reshipCarrierCode;               // 교환 전용 (06 이 채운다)

    @Column(name = "requester_name", length = 100)
    private String requesterName;                   // D19 — 이름만

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;               // 쿠팡 createdAt — 추적 슬라이스의 기준

    @Column(name = "platform_modified_at")
    private LocalDateTime platformModifiedAt;       // 쿠팡 modifiedAt

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;

    /** 주문 라인 연결 여부 — 화면이 "주문 미연결" 배지를 띄우는 근거. */
    public boolean isLinked() {
        return orderItem != null;
    }
}
