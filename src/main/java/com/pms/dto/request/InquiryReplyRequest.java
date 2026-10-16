package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Customer inquiry reply request — POST /api/admin/inquiries/{id}/replies (FEATURE_2609_23 / 04).
 *
 * <p>⚠️ No {@code @Size} — the length limit <b>differs per type</b> (product Q&A 1 / call center 2 / 11st
 * non-delivery 1~200) and its only owner is {@code InquiryReplyPolicy} (D5). A number in the DTO would split the
 * {@code replyCapability.minLength} the screen gets from the server check.
 * <p>⚠️ {@code parentReplyId} is not accepted — only the value the server picks by {@code transfer_status} is used.
 * <p>{@code reasonCode} · {@code carrierCode} · {@code invoiceNumber} are read only for the 11st non-delivery form
 * (FEATURE_2610_17 / D68) and checked by {@code InquiryReplyPolicy}; every other form ignores them.
 */
public record InquiryReplyRequest(
        @Schema(description = "Reply body sent to the customer", example = "내일 출고 예정입니다.")
        @NotBlank(message = "답변 내용을 입력해 주세요.")
        String content,
        @Schema(description = "11st non-delivery handling reason (101~104)", example = "102")
        String reasonCode,
        @Schema(description = "11st carrier code — reason 102 only", example = "00034")
        String carrierCode,
        @Schema(description = "Re-shipping invoice number — reason 102 only", example = "123456789012")
        String invoiceNumber) {

    /** A plain text reply. */
    public InquiryReplyRequest(String content) {
        this(content, null, null, null);
    }
}
