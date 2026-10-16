package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * <b>One row per attempt</b> of a seller cancel before shipping (FEATURE_2609_25 / PLAN D8) — the Coupang cancel and
 * the 11st sale refusal 1638 alike (FEATURE_2610_17 / D70).
 *
 * <p>Success and failure are both kept. It does three jobs:
 * <ol>
 *   <li>the audit trail of an irreversible write (a cancel has no withdraw API and lowers the seller score)</li>
 *   <li>the only place the chosen <b>reason</b> survives — Coupang overwrites the reason on INSTRUCT lines, and the
 *       11st reason text is kept nowhere else</li>
 *   <li>the marketplace's failure wording and receipt number</li>
 * </ol>
 * Folding it into {@code order_line} columns would keep only the last attempt and do none of the three.
 *
 * <p>⚠️ <b>No UNIQUE constraint</b> — retries of a failure piling up as several rows is normal.
 *
 * <p>⚠️ Lines the server filtered out ({@code skipped}/{@code unsupported}) are not kept. The rule is not "was it
 * sent" but <b>"is it reported to the user as a failure"</b> — a line that could not even be sent for lack of a WING
 * ID is reported as failed, so it is kept.
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

    /**
     * The cancel quantity — Coupang: the requested quantity (partial cancel supported) · 11st: the line's remaining
     * quantity right before sending, as 1638 cancels the whole line (D70).
     */
    @Column(nullable = false)
    private Integer quantity;

    /**
     * The reason the user picked — our enum name; this, not the Coupang code, is the source. Required on a Coupang
     * row (the server enforces it — FEATURE_2610_17 / D71); {@code null} on an 11st row, whose reason lives in
     * {@code platform_reason_code} · {@code reason_text} (D70).
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private OrderCancelReason reason;

    /**
     * The marketplace reason code actually sent — Coupang {@code middleCancelCode} · 11st 1638 {@code ordCnRsnCd}
     * (D70). The value sent at the time stays even if the mapping changes.
     */
    @Column(name = "platform_reason_code", length = 20)
    private String platformReasonCode;

    /** The reason text the seller wrote for an 11st sale refusal (1~200, D70); {@code null} on a Coupang row. */
    @Column(name = "reason_text", length = 200)
    private String reasonText;

    /**
     * The raw <b>marketplace</b> status <b>right before</b> sending — Coupang {@code coupang_order_line.platform_status}
     * (ACCEPT/INSTRUCT, the evidence of instant cancel vs shipment stop) · 11st {@code elevenst_order_line.platform_status}
     * (201 · 202 · 301, D70).
     */
    @Column(name = "status_at_send", length = 30)
    private String statusAtSend;

    @Column(nullable = false)
    private boolean succeeded;

    /** The Coupang receipt number on success ({@code receiptId} of {@code receiptMap}); {@code null} on an 11st row. */
    @Column(name = "receipt_id", length = 50)
    private String receiptId;

    /**
     * Coupang {@code CANCEL} (instant cancel) / {@code STOP_SHIPMENT} (shipment stop), null when unread · 11st
     * {@code ELEVENST_SALE_REFUSAL} (D70).
     */
    @Column(name = "receipt_type", length = 30)
    private String receiptType;

    /** The marketplace's own result code (Coupang {@code code} · 11st {@code result_code}) — never translated. */
    @Column(name = "result_code", length = 50)
    private String resultCode;

    /**
     * The marketplace's own wording (Coupang {@code message} · 11st {@code result_text}) — never translated or
     * summarised (truncated to 1000).
     */
    @Column(name = "result_message", length = 1000)
    private String resultMessage;

    /** 실행한 사용자(인증 principal 의 username = 이메일). 인증이 없으면 null. */
    @Column(name = "created_by", length = 100)
    private String createdBy;
}
