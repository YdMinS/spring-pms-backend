package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * 고객문의 답변 1건 (FEATURE_2609_23 / PLAN §3 · D6).
 *
 * 두 쿠팡 API 모두 "질문 1 + 답변 N" 모양이라({@code commentDtoList} / {@code replies}) 본문은 헤더
 * 컬럼으로 두고 답변만 자식 테이블로 내린다 — 본문까지 자식이면 목록 미리보기가 매번 join 이거나
 * 비정규화 컬럼이 생긴다.
 *
 * <p>UNIQUE(customer_inquiry_id, external_reply_id) 로 멱등 upsert 된다. 기본은 <b>삭제 없이 추가/갱신만</b>
 * 이다 — 플랫폼이 답변을 지우는 API 가 없어서 사라지는 일이 없고, 지우기 시작하면 전송 직후의 로컬
 * 반영분이 다음 동기화에서 증발한다.
 *
 * <p>⚠️ {@link #authorName} 은 쿠팡 <b>상담사</b> 이름({@code receptionistName})이다 — 고객 PII 가
 * 아니라 저장한다(D13). 구매자 연락처·이메일은 어떤 컬럼에도 담지 않는다.
 */
@Entity
@Table(name = "customer_inquiry_reply",
        uniqueConstraints = @UniqueConstraint(name = "uq_inquiry_reply_inquiry_external",
                columnNames = {"customer_inquiry_id", "external_reply_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CustomerInquiryReply extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_inquiry_id", nullable = false)
    private CustomerInquiry inquiry;

    @Column(name = "external_reply_id", nullable = false, length = 100)
    private String externalReplyId;              // inquiryCommentId / answerId

    /**
     * 쿠팡 {@code parentAnswerId}.
     *
     * 🔴 <b>고객센터 답변 전송(04)이 쓰는 값</b>이다 — 전송 페이로드의 필수 필드라 조회 단계에서
     * 반드시 보존해 둬야 한다(PLAN D5 의 {@code replyCapability.parentReplyId} 가 이 값에서 나온다).
     * 상품문의에는 없다(null).
     */
    @Column(name = "parent_external_reply_id", length = 100)
    private String parentExternalReplyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, length = 20)
    private InquiryAuthorRole authorRole;

    @Column(name = "author_name", length = 100)
    private String authorName;                   // receptionistName — 상품문의는 null

    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 쿠팡 {@code partnerTransferStatus} 원문 (고객센터 전용).
     *
     * {@code requestAnswer} 가 곧 "우리가 답할 차례" 라는 신호이며 상태 정규화(§3.1)와
     * 답변 가능 판정(04)의 근거다 — 정규화하지 말고 원문 그대로 둔다.
     */
    @Column(name = "transfer_status", length = 50)
    private String transferStatus;

    @Column(name = "replied_at")
    private LocalDateTime repliedAt;
}
