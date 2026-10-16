package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Customer inquiry header (FEATURE_2609_23 / PLAN §3 · D1).
 *
 * Inquiries of two different types (product Q&A · call-center) live in <b>one table</b> (D1). The two Coupang responses
 * share not one field, but the user does the same thing with both, so only parsing and sending differ — splitting the
 * table per type or platform would multiply queries, screens and services by that count (same reasoning as
 * {@link OrderClaim}). 11st product Q&A and urgent notices use the same two types (FEATURE_2610_17 / D16 · D26).
 *
 * <p>The channel is not a new entity but {@link MarketplaceAccount} itself (D2). Idempotent upsert by
 * UNIQUE(marketplace_account_id, inquiry_type, external_inquiry_id), and {@code inquiry_type} <b>must</b> be in the key —
 * product-Q&A {@code inquiryId} and call-center {@code inquiryId} are separate sequences and can collide.
 *
 * <p>Only <b>one</b> {@code external_order_id} is stored for the order link (D14). When a product Q&A has 2+
 * {@code orderIds} the first is used with a warning. A failed match still stores the inquiry with {@code orderLine}
 * null (D15) — most product questions have no order, so no link is normal, and the right panel then still shows the
 * product: {@link ProductListing} is linked by {@code external_item_id} (Coupang vendorItemId) or by the 11st product
 * number ({@code external_product_id}, FEATURE_2610_17 / D16).
 *
 * <p>🔴 No PII is stored (D13) — do not add {@code buyerPhone} · {@code buyerEmail} columns.
 * Call-center responses carry the buyer's contact, but it is not even parsed. The Coupang agent's name
 * ({@code receptionistName}) is not customer PII and is stored on the reply row.
 *
 * <p>⚠️ {@link #replies} is <b>not fetched by the list query</b> (PLAN §3) — only the detail draws the thread.
 * The write path ({@code InquiryUpserter}) does not touch this collection either and goes through
 * {@code CustomerInquiryReplyRepository} only: only an uninitialized collection keeps {@code toBuilder()} +
 * {@code save()} from replacing the orphanRemoval collection.
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
    private String externalInquiryId;               // Coupang inquiryId (one sequence per type) · 11st brdInfoNo / emerNtceSeq

    @Column(name = "external_item_id", length = 100)
    private String externalItemId;                  // Coupang vendorItemId (call center: array → first) · 11st ordPrdSeq (urgent notice)

    @Column(name = "external_order_id", length = 100)
    private String externalOrderId;                 // nullable — 주문 없는 상품문의가 정상이다 (D14)

    @Column(name = "external_product_id", length = 100)
    private String externalProductId;               // product Q&A only: Coupang sellerProductId · 11st prdNo (brdInfoClfNo)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id")
    private ProductListing productListing;          // cell linked by Coupang vendorItemId / 11st prdNo (D15 · FEATURE_2610_17 / D16) — null on failure

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
    private String platformStatus;                  // raw (Coupang ANSWERED / progress/requestAnswer ... · 11st Y·N / emerNtceCrntCd)

    @Column(name = "inquired_at", nullable = false)
    private LocalDateTime inquiredAt;               // Coupang inquiryAt · 11st createDt — base of the slices (D8) and sorting

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
