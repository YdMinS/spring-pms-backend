package com.pms.service.inquiry;

import com.pms.domain.CoupangAccountCredential;
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
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.elevenst.ElevenstSellerChoices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryReplyPolicy {

    /** 답변 전송(04)이 성공 직후 넣는 로컬 임시 행의 식별자 접두 — {@code InquiryUpserter} 와 같은 값이다. */
    private static final String LOCAL_REPLY_PREFIX = "local-";

    /** 🔴 고객센터에서 "이제 판매자가 답할 차례" 를 뜻하는 쿠팡 원문. {@code parentAnswerId} 의 출처다. */
    private static final String TRANSFER_REQUEST_ANSWER = "requestAnswer";

    private static final int MAX_LENGTH = 1000;
    /** 상품문의는 한 글자도 허용, 고객센터는 쿠팡이 2자 미만을 거절한다 — 유형별로 다르다. */
    private static final int MIN_LENGTH_PRODUCT_QNA = 1;
    private static final int MIN_LENGTH_CALL_CENTER = 2;
    /** FEATURE_2610_17 / D27 · D68: 11st takes at least 1 character for every type. */
    private static final int MIN_LENGTH_ELEVENST = 1;

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
        int minLength = (inquiry.getPlatform() == Platform.ELEVENST) ? MIN_LENGTH_ELEVENST : minLength(type);
        String parentReplyId = parentReplyId(inquiry, replies);
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

    /**
     * 계정의 WING 사용자 ID({@code replyBy} 원천, 2609_26 이후 {@link CoupangAccountCredential} 소유).
     *
     * <p>차단 사유 판정은 예외를 던지면 안 되므로 계정·자격증명 부재를 null 로 접는다 —
     * null = "원천 없음" = 답변 차단이며, 전송 경로의 400 과 결과가 같다.
     */
    private static String wingUserId(MarketplaceAccount account) {
        if (account == null || account.getPlatform() != Platform.COUPANG
                || account.getCoupangCredential() == null) {
            return null;
        }
        return CoupangCredentials.of(account).getVendorUserId();
    }

    /** 차단 사유 — 위에서부터 먼저 맞는 것 하나만. 가능하면 null. */
    private String blockReason(CustomerInquiry inquiry, List<CustomerInquiryReply> replies) {
        MarketplaceAccount account = inquiry.getMarketplaceAccount();

        // 1) 어댑터 없음 = 아직 붙지 않은 플랫폼. 플랫폼 코드를 사용자에게 그대로 노출하지 않는다.
        if (resolve(inquiry.getPlatform()).isEmpty()) {
            String alias = (account == null || account.getAccountAlias() == null)
                    ? "이" : account.getAccountAlias();
            return alias + " 채널은 아직 답변 전송을 지원하지 않습니다.";
        }
        // 2) replyBy 원천이 비어 있음 (D18). 전송 시점이 아니라 조회 시점에 잠근다.
        String wingUserId = wingUserId(account);
        if (inquiry.getPlatform() == Platform.COUPANG && (wingUserId == null || wingUserId.isBlank())) {
            return "채널에 WING 사용자 ID가 없습니다. 판매자 > 채널 수정에서 입력해 주세요.";
        }
        // 3)·4) 상태. 답변은 미답변 건에서만 열린다.
        if (inquiry.getStatus() == InquiryStatus.ANSWERED) {
            return "이미 답변한 문의입니다.";
        }
        if (inquiry.getStatus() == InquiryStatus.CLOSED || inquiry.getStatus() == InquiryStatus.STALE) {
            return "종료된 문의입니다.";
        }
        // 5) Drift guard — by §3.1 an UNANSWERED Coupang call-center inquiry always has a requestAnswer reply. Hitting
        //    this means the parser (01) or the status normalization drifted. 11st urgent notices have no such reply
        //    and skip the check (FEATURE_2610_17 / D44 · D45).
        if (inquiry.getPlatform() == Platform.COUPANG && inquiry.getInquiryType() == InquiryType.CALL_CENTER
                && requestAnswerReply(replies).isEmpty()) {
            log.warn("Call-center inquiry is UNANSWERED but has no requestAnswer reply — parser or status "
                    + "normalization drifted: inquiry={} platformStatus={}",
                    inquiry.getId(), inquiry.getPlatformStatus());
            return "판매자 답변이 요청된 문의가 아닙니다.";
        }
        return null;
    }

    private int minLength(InquiryType type) {
        return (type == InquiryType.CALL_CENTER) ? MIN_LENGTH_CALL_CENTER : MIN_LENGTH_PRODUCT_QNA;
    }

    /** Parent reply id of a Coupang call-center inquiry only; product Q&A and 11st are always null. */
    private String parentReplyId(CustomerInquiry inquiry, List<CustomerInquiryReply> replies) {
        if (inquiry.getInquiryType() != InquiryType.CALL_CENTER || inquiry.getPlatform() != Platform.COUPANG) {
            return null;
        }
        return requestAnswerReply(replies)
                .map(CustomerInquiryReply::getExternalReplyId)
                .orElse(null);
    }

    /**
     * {@code transfer_status = requestAnswer} 인 reply 중 <b>가장 최근</b> 것.
     *
     * ⚠️ {@code external_reply_id} 가 {@code local-} 로 시작하는 행(04 가 전송 직후 넣은 로컬 임시 행)은
     * 후보에서 <b>제외</b>한다 — 쿠팡에 없는 식별자라 {@code parentAnswerId} 로 보내면 400 이다.
     */
    private Optional<CustomerInquiryReply> requestAnswerReply(List<CustomerInquiryReply> replies) {
        if (replies == null) {
            return Optional.empty();
        }
        return replies.stream()
                .filter(reply -> TRANSFER_REQUEST_ANSWER.equalsIgnoreCase(reply.getTransferStatus()))
                .filter(reply -> reply.getExternalReplyId() != null
                        && !reply.getExternalReplyId().startsWith(LOCAL_REPLY_PREFIX))
                .max(Comparator
                        .comparing(CustomerInquiryReply::getRepliedAt,
                                Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                        .thenComparing(CustomerInquiryReply::getId,
                                Comparator.nullsFirst(Comparator.<Long>naturalOrder())));
    }

    /** 플랫폼이 맞는 어댑터 1개 — {@code ClaimActionServiceImpl.resolve()} 와 동형. */
    Optional<InquiryReplyAdapter> resolve(Platform platform) {
        if (platform == null) {
            return Optional.empty();
        }
        return adapters.stream().filter(adapter -> platform.equals(adapter.platform())).findFirst();
    }
}
