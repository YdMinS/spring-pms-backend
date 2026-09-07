package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 고객문의 헤더 (FEATURE_2609_23 / PLAN §3 · D1).
 *
 * 유형이 다른 두 문의(상품문의 · 고객센터문의)를 <b>단일 테이블</b>에 담는다(D1). 두 쿠팡 응답은 한
 * 필드도 겹치지 않지만 사용자가 하는 일은 같아서, 실제로 다른 것은 파싱과 전송뿐이다 — 테이블을
 * 유형·플랫폼마다 나누면 조회·화면·서비스가 그 수만큼 늘어난다({@link OrderClaim} 과 같은 판단).
 *
 * <p>채널은 새 엔티티가 아니라 {@link MarketplaceAccount} 그대로다(D2). UNIQUE(marketplace_account_id,
 * inquiry_type, external_inquiry_id) 로 멱등 upsert 되며, {@code inquiry_type} 이 키에 <b>있어야 한다</b> —
 * 상품문의 {@code inquiryId} 와 고객센터 {@code inquiryId} 는 다른 시퀀스라 같은 숫자가 겹칠 수 있다.
 *
 * <p>주문 연결은 {@code external_order_id} <b>1건</b>만 저장한다(D14). 상품문의 {@code orderIds} 가
 * 2건 이상이면 첫 건만 쓰고 경고만 남긴다. 매칭에 실패해도 문의는 저장하고 {@code orderLine} 만
 * null 로 둔다(D15) — 상품문의는 애초에 주문 없는 질문이 다수라 미연결이 정상이며, 그때 우측 패널이
 * 상품 정보라도 보여주도록 {@code external_item_id}(vendorItemId)로 {@link ProductListing} 을 잇는다.
 *
 * <p>🔴 PII 를 저장하지 않는다(D13) — {@code buyerPhone}·{@code buyerEmail} 컬럼을 추가하지 말 것.
 * 고객센터 응답에는 구매자 연락처가 실려 오지만 파싱조차 하지 않는다. 쿠팡 상담사 이름
 * ({@code receptionistName})은 고객 PII 가 아니라 답변 행에 저장한다.
 *
 * <p>⚠️ {@link #replies} 는 <b>목록 조회에서 fetch 하지 않는다</b>(PLAN §3) — 상세만 스레드를 그린다.
 * 쓰기 경로({@code InquiryUpserter})도 이 컬렉션을 건드리지 않고 {@code CustomerInquiryReplyRepository}
 * 로만 다룬다: 초기화되지 않은 컬렉션이라야 {@code toBuilder()} 재조립 + {@code save()} 가
 * orphanRemoval 컬렉션을 갈아끼우지 않는다.
 */
@Entity
@Table(name = "customer_inquiry",
        uniqueConstraints = @UniqueConstraint(name = "uq_inquiry_account_type_external",
                columnNames = {"marketplace_account_id", "inquiry_type", "external_inquiry_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CustomerInquiry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 채널 = 판매자 × 플랫폼 (D2). "채널" 이라는 새 엔티티를 만들지 않는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private Platform platform;                      // orders 관례를 따라 둔다

    @Enumerated(EnumType.STRING)
    @Column(name = "inquiry_type", nullable = false, length = 20)
    private InquiryType inquiryType;

    @Column(name = "external_inquiry_id", nullable = false, length = 100)
    private String externalInquiryId;               // 쿠팡 inquiryId (유형마다 다른 시퀀스)

    @Column(name = "external_item_id", length = 100)
    private String externalItemId;                  // vendorItemId (고객센터는 배열 → 첫 값)

    @Column(name = "external_order_id", length = 100)
    private String externalOrderId;                 // nullable — 주문 없는 상품문의가 정상이다 (D14)

    @Column(name = "external_product_id", length = 100)
    private String externalProductId;               // sellerProductId — 상품문의 전용

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id")
    private ProductListing productListing;          // vendorItemId 로 연결한 셀 (D15) — 실패 시 null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id")
    private OrderLine orderLine;                    // 매칭 실패·모호하면 null (D15)

    @Column(name = "item_name", length = 500)
    private String itemName;                        // 미연결 시 화면 공백 방지 (셀 연결에서 채우기도 한다)

    @Column(columnDefinition = "TEXT")
    private String content;                         // 문의 본문 — 헤더 컬럼이다 (D6)

    @Column(length = 100)
    private String category;                        // receiptCategory 원문 — 고객센터 전용

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryStatus status;                   // 정규화 상태 (§3.1)

    @Column(name = "platform_status", nullable = false, length = 50)
    private String platformStatus;                  // 원문 (ANSWERED / progress/requestAnswer ...)

    @Column(name = "inquired_at", nullable = false)
    private LocalDateTime inquiredAt;               // 쿠팡 inquiryAt — 슬라이스(D8)·정렬의 기준

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    /**
     * 답변 스레드. 상세가 곧 스레드라 연관을 두지만 <b>목록은 읽지 않는다</b>(PLAN §3).
     *
     * ⚠️ 쓰기 경로에서 이 컬렉션을 초기화하지 말 것 — {@code toBuilder()} 로 헤더를 갱신하는 순간
     * orphanRemoval 컬렉션이 교체된 것으로 취급될 수 있다. 답변 적재는 리포지토리로만 한다.
     */
    @OneToMany(mappedBy = "inquiry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CustomerInquiryReply> replies = new ArrayList<>();

    /** 주문 라인 연결 여부 — 화면이 "주문 미연결" 배지를 띄우는 근거. */
    public boolean isLinked() {
        return orderLine != null;
    }
}
