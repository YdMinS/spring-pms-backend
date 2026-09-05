package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * 클레임 처리 액션의 <b>시도 1건 = 1행</b> (FEATURE_2609_21 / PLAN §4 · D5).
 *
 * <p>성공·실패를 모두 남긴다. 세 가지 일을 한다:
 * <ol>
 *   <li>되돌릴 수 없는 쓰기(반품 승인 = 환불 확정)의 감사기록 — "언제 무엇을 왜 보냈나"</li>
 *   <li>중복 전송 가드의 조회 대상(D6) — {@code succeeded=true} 행이 있으면 재전송 거부</li>
 *   <li>쿠팡 실패 원문 보존</li>
 * </ol>
 * claim 에 컬럼 2~3개로 뭉개면 마지막 1건만 남아 셋 다 못 한다.
 *
 * <p>⚠️ <b>UNIQUE 제약이 없다</b> — 실패 재시도가 여러 행으로 쌓이는 것이 정상이다.
 * 중복 방지는 제약이 아니라 조회로 한다.
 *
 * <p>⚠️ {@code requestSummary} 에 PII 를 담지 말 것 — 송장번호·거부코드·수량까지만.
 */
@Entity
@Table(name = "order_claim_action")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OrderClaimAction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * 액션을 건 claim 라인. 쿠팡 액션은 <b>접수 단위</b>로 적용되지만 기록은 클릭한 라인에 남긴다 —
     * 중복 가드가 형제 라인 전체를 조회하므로(D6) 어느 라인에 남았든 접수 전체에서 보인다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_claim_id", nullable = false)
    private OrderClaim orderClaim;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ClaimAction action;

    @Column(nullable = false)
    private boolean succeeded;

    /** 전송 <b>직전</b>의 {@code order_claim.platform_status} — 사후에 "왜 이때 보냈나"를 설명한다. */
    @Column(name = "platform_status_at_send", length = 30)
    private String platformStatusAtSend;

    /** 보낸 값 요약. {@code k=v} 를 {@code ,} 로 이은 한 줄(예 {@code carrier=CJGLS,invoice=123,cancelCount=4}). */
    @Column(name = "request_summary", length = 500)
    private String requestSummary;

    /** 쿠팡 응답 {@code code} 원문 — 번역·요약 금지(D15). */
    @Column(name = "result_code", length = 50)
    private String resultCode;

    /** 쿠팡 응답 {@code message} 원문 — 번역·요약 금지(D15). */
    @Column(name = "result_message", length = 1000)
    private String resultMessage;

    /** 실행한 사용자(인증 principal 의 username = 이메일). 인증이 없으면 null. */
    @Column(name = "created_by", length = 100)
    private String createdBy;
}
