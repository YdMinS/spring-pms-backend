package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * 발송 전 주문 취소의 <b>시도 1건 = 1행</b> (FEATURE_2609_25 / PLAN D8).
 *
 * <p>성공·실패를 모두 남긴다. 세 가지 일을 한다:
 * <ol>
 *   <li>되돌릴 수 없는 쓰기(취소는 철회 API 가 없고 판매자 점수가 깎인다)의 감사기록</li>
 *   <li>사용자가 고른 <b>사유</b>의 유일한 보존처 — INSTRUCT 건은 쿠팡이 사유를 덮어쓴다</li>
 *   <li>쿠팡 실패 원문·접수번호 보존</li>
 * </ol>
 * {@code order_line} 에 컬럼으로 뭉개면 마지막 1건만 남아 셋 다 못 한다.
 *
 * <p>⚠️ <b>UNIQUE 제약이 없다</b> — 실패 재시도가 여러 행으로 쌓이는 것이 정상이다.
 *
 * <p>⚠️ 서버가 걸러낸 건({@code skipped}/{@code unsupported})은 남기지 않는다. 기준은 "전송했는가"가
 * 아니라 <b>"사용자에게 실패로 보고되는가"</b> 다 — WING ID 가 없어 전송조차 못 한 건은 실패로
 * 보고되므로 남긴다.
 */
@Entity
@Table(name = "order_cancel_action")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OrderCancelAction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension. Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 취소를 건 주문 라인(옵션 단위). 취소 단위가 라인×수량이라 박스가 아니라 라인에 남는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine orderLine;

    /** 요청한 취소 수량(부분취소 지원). */
    @Column(nullable = false)
    private Integer quantity;

    /** 사용자가 고른 사유 — 우리 enum 이름. 쿠팡 코드가 아니라 여기 것이 원본이다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderCancelReason reason;

    /** 실제 전송한 {@code middleCancelCode}. 매핑이 바뀌어도 그때 보낸 값이 남는다. */
    @Column(name = "platform_reason_code", length = 20)
    private String platformReasonCode;

    /** 전송 <b>직전</b>의 <b>플랫폼</b> 상태 원문({@code coupang_order_line.platform_status} = ACCEPT/INSTRUCT) — 즉시취소였는지 출고중지였는지의 근거. */
    @Column(name = "status_at_send", length = 30)
    private String statusAtSend;

    @Column(nullable = false)
    private boolean succeeded;

    /** 성공 시 쿠팡 접수번호({@code receiptMap} 의 {@code receiptId}). */
    @Column(name = "receipt_id", length = 50)
    private String receiptId;

    /** {@code CANCEL}(즉시취소) / {@code STOP_SHIPMENT}(출고중지). 못 읽었으면 null. */
    @Column(name = "receipt_type", length = 20)
    private String receiptType;

    /** 쿠팡 응답 {@code code} 원문 — 번역·요약 금지. */
    @Column(name = "result_code", length = 50)
    private String resultCode;

    /** 쿠팡 응답 {@code message} 원문 — 번역·요약 금지(truncate 1000). */
    @Column(name = "result_message", length = 1000)
    private String resultMessage;

    /** 실행한 사용자(인증 principal 의 username = 이메일). 인증이 없으면 null. */
    @Column(name = "created_by", length = 100)
    private String createdBy;
}
