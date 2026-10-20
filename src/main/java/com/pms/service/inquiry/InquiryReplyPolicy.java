package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.ReplyCapability;
import com.pms.service.CarrierCodeService;
import com.pms.service.CarrierOption;
import com.pms.service.InvoiceNumbers;
import com.pms.service.claim.ActionChoice;
import com.pms.service.elevenst.ElevenstSellerChoices;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 답변 가능 여부 판정 (FEATURE_2609_23 / PLAN D5 · 04 Step 2).
 *
 * 🔴 <b>판정의 유일한 자리</b>다. 조회 응답({@code GET /api/inquiries/{id}})과 전송 서비스가 <b>같은</b>
 * 메서드를 부른다 — 화면이 잠갔다는 것을 서버가 신뢰하지 않으므로 전송 직전에도 여기서 다시 본다.
 *
 * <p>🔴 <b>역할(ADMIN) 판정을 넣지 않는다.</b> 비-ADMIN 에게도 {@code canReply = true} 가 내려가고,
 * 화면이 가리며 최종 방어는 컨트롤러의 {@code @PreAuthorize} 403 이다. ({@code ClaimActionService}
 * 는 서버에서 역할로 잠그지만 그건 <b>목록 응답에 버튼 목록을 싣는</b> 구조라 그렇다 — 여기 capability
 * 는 단건 응답이고 조회 API 는 {@code authenticated()} 다.)
 *
 * <p>차단 사유는 <b>위에서부터 먼저 맞는 것 하나만</b> 쓴다(어댑터 없음 → WING ID 없음 → 이미 답변 →
 * 종료됨 → 요청되지 않은 고객센터 건). 문구는 서버가 완성해 내려보낸다.
 */
@Component
@RequiredArgsConstructor
public class InquiryReplyPolicy {

    private static final int MAX_LENGTH = 1000;
    /**
     * Minimum length shown for an inquiry whose marketplace has no reply adapter — the reply is blocked anyway (step
     * 1); the values stay what that response carried before the adapters owned the minimum (FEATURE_2610_23 / D3 ·
     * D14).
     */
    private static final int UNSUPPORTED_MIN_LENGTH_PRODUCT_QNA = 1;
    private static final int UNSUPPORTED_MIN_LENGTH_CALL_CENTER = 2;

    /**
     * Cannot be undone (D17) — Coupang has no API to edit or delete a reply. True for every type (the 11st
     * non-delivery handling too, FEATURE_2610_17 / D68).
     */
    private static final boolean ONCE = true;
    /** FEATURE_2610_17 / D68: the 11st non-delivery detail text is 1~200 characters (same value as D39 ①). */
    private static final int MAX_LENGTH_NON_DELIVERY = 200;

    private final List<InquiryReplyAdapter> adapters;
    /** FEATURE_2610_17 / D68: checks the re-shipping carrier and gives its name for the record line. */
    private final CarrierCodeService carrierCodeService;

    /**
     * The checked 11st non-delivery input (FEATURE_2610_17 / D68).
     *
     * @param recordText the reply line stored after a success: 「{reason name} — {detail}」, plus
     *                   「 (재발송 {carrier name} {invoice})」 for reason 102
     */
    public record NonDeliveryInput(String reasonCode, String carrierCode, String invoiceNumber, String recordText) {
    }

    /**
     * 지금 이 문의에 답변할 수 있는가.
     *
     * @param replies 이 문의의 답변 스레드 전체(조회한 쪽이 넘긴다 — 정책은 DB 를 다시 보지 않는다)
     */
    public ReplyCapability evaluate(CustomerInquiry inquiry, List<CustomerInquiryReply> replies) {
        InquiryType type = inquiry.getInquiryType();
        // FEATURE_2610_23 / D12 · D21: the marketplace's own limits come from its reply adapter.
        Optional<InquiryReplyAdapter> adapter = resolve(inquiry.getPlatform());
        int minLength = adapter.map(a -> a.minLength(type)).orElseGet(() -> unsupportedMinLength(type));
        String parentReplyId = adapter.map(a -> a.parentReplyId(inquiry, replies)).orElse(null);
        boolean nonDelivery = type == InquiryType.NON_DELIVERY;
        int maxLength = nonDelivery ? MAX_LENGTH_NON_DELIVERY : MAX_LENGTH;
        String form = nonDelivery ? ReplyCapability.FORM_NON_DELIVERY : ReplyCapability.FORM_TEXT;
        List<ActionChoice> reasons =
                nonDelivery ? ElevenstSellerChoices.NON_DELIVERY_REASONS : List.of();

        String reason = blockReason(inquiry, replies);
        return new ReplyCapability(reason == null, reason, minLength, maxLength, ONCE, parentReplyId, form, reasons);
    }

    /**
     * Checks the 11st non-delivery form (FEATURE_2610_17 / D68): a listed reason is required; reason 102 also needs
     * an 11st carrier and an invoice number (delivery method parcel, set by the adapter). Other reasons send neither.
     *
     * @param detail the stripped detail text, already length-checked by the caller
     * @throws IllegalArgumentException missing or unknown reason · carrier · invoice (→ 400)
     */
    public NonDeliveryInput nonDelivery(CustomerInquiry inquiry, String detail, String reasonCode, String carrierCode,
                                        String invoiceNumber) {
        String code = reasonCode == null ? null : reasonCode.trim();
        String label = ElevenstSellerChoices.requireLabel(ElevenstSellerChoices.NON_DELIVERY_REASONS, code,
                "처리 사유를 선택하세요");
        if (!ElevenstSellerChoices.RESEND.equals(code)) {
            return new NonDeliveryInput(code, null, null, label + " — " + detail);
        }
        String carrier = carrierCodeService.validateDeliveryCompanyCode(carrierCode, inquiry.getPlatform());
        String invoice = InvoiceNumbers.normalizeRequired(invoiceNumber);
        String carrierName = carrierCodeService.findOptions(inquiry.getPlatform()).stream()
                .filter(option -> carrier.equals(option.deliveryCompanyCode()))
                .map(CarrierOption::carrierName)
                .findFirst()
                .orElse(carrier);
        return new NonDeliveryInput(code, carrier, invoice,
                label + " — " + detail + " (재발송 " + carrierName + " " + invoice + ")");
    }

    /** 차단 사유 — 위에서부터 먼저 맞는 것 하나만. 가능하면 null. */
    private String blockReason(CustomerInquiry inquiry, List<CustomerInquiryReply> replies) {
        MarketplaceAccount account = inquiry.getMarketplaceAccount();

        // 1) 어댑터 없음 = 아직 붙지 않은 플랫폼. 플랫폼 코드를 사용자에게 그대로 노출하지 않는다.
        Optional<InquiryReplyAdapter> adapter = resolve(inquiry.getPlatform());
        if (adapter.isEmpty()) {
            String alias = (account == null || account.getAccountAlias() == null)
                    ? "이" : account.getAccountAlias();
            return alias + " 채널은 아직 답변 전송을 지원하지 않습니다.";
        }
        // 2) The account condition of the marketplace (Coupang: the replyBy source, D18), checked when the inquiry is
        //    read, not when it is sent (FEATURE_2610_23 / D12).
        String accountReason = adapter.get().accountBlockReason(account);
        if (accountReason != null) {
            return accountReason;
        }
        // 3)·4) 상태. 답변은 미답변 건에서만 열린다.
        if (inquiry.getStatus() == InquiryStatus.ANSWERED) {
            return "이미 답변한 문의입니다.";
        }
        if (inquiry.getStatus() == InquiryStatus.CLOSED || inquiry.getStatus() == InquiryStatus.STALE) {
            return "종료된 문의입니다.";
        }
        // 5) The reply-thread condition of the marketplace (Coupang: the requestAnswer drift guard; 11st urgent
        //    notices have no such reply and skip it — FEATURE_2610_17 / D44 · D45 · FEATURE_2610_23 / D12).
        return adapter.get().threadBlockReason(inquiry, replies);
    }

    private static int unsupportedMinLength(InquiryType type) {
        return (type == InquiryType.CALL_CENTER) ? UNSUPPORTED_MIN_LENGTH_CALL_CENTER : UNSUPPORTED_MIN_LENGTH_PRODUCT_QNA;
    }

    /** 플랫폼이 맞는 어댑터 1개 — {@code ClaimActionServiceImpl.resolve()} 와 동형. */
    Optional<InquiryReplyAdapter> resolve(Platform platform) {
        if (platform == null) {
            return Optional.empty();
        }
        return adapters.stream().filter(adapter -> platform.equals(adapter.platform())).findFirst();
    }
}
