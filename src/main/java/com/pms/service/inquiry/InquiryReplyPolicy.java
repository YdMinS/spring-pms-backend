package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.dto.response.ReplyCapability;
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

    /** 되돌릴 수 없다(D17) — 쿠팡은 답변 수정·삭제 API 가 없다. 두 유형 모두 true. */
    private static final boolean ONCE = true;

    private final List<InquiryReplyAdapter> adapters;

    /**
     * 지금 이 문의에 답변할 수 있는가.
     *
     * @param replies 이 문의의 답변 스레드 전체(조회한 쪽이 넘긴다 — 정책은 DB 를 다시 보지 않는다)
     */
    public ReplyCapability evaluate(CustomerInquiry inquiry, List<CustomerInquiryReply> replies) {
        InquiryType type = inquiry.getInquiryType();
        int minLength = minLength(type);
        String parentReplyId = parentReplyId(inquiry, replies);

        String reason = blockReason(inquiry, replies);
        if (reason != null) {
            return new ReplyCapability(false, reason, minLength, MAX_LENGTH, ONCE, parentReplyId);
        }
        return new ReplyCapability(true, null, minLength, MAX_LENGTH, ONCE, parentReplyId);
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
        if (account == null || account.getVendorUserId() == null || account.getVendorUserId().isBlank()) {
            return "채널에 WING 사용자 ID가 없습니다. 판매자 > 채널 수정에서 입력해 주세요.";
        }
        // 3)·4) 상태. 답변은 미답변 건에서만 열린다.
        if (inquiry.getStatus() == InquiryStatus.ANSWERED) {
            return "이미 답변한 문의입니다.";
        }
        if (inquiry.getStatus() == InquiryStatus.CLOSED || inquiry.getStatus() == InquiryStatus.STALE) {
            return "종료된 문의입니다.";
        }
        // 5) 데이터 드리프트 방어 — §3.1 정의상 UNANSWERED 인 고객센터 건은 requestAnswer reply 를
        //    반드시 갖는다. 여기서 걸리면 파서(01)나 상태 정규화가 어긋났다는 신호다.
        if (inquiry.getInquiryType() == InquiryType.CALL_CENTER && requestAnswerReply(replies).isEmpty()) {
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

    /** 고객센터 전용 상위 답변 식별자. 상품문의는 항상 null 이다. */
    private String parentReplyId(CustomerInquiry inquiry, List<CustomerInquiryReply> replies) {
        if (inquiry.getInquiryType() != InquiryType.CALL_CENTER) {
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
    Optional<InquiryReplyAdapter> resolve(String platform) {
        if (platform == null) {
            return Optional.empty();
        }
        return adapters.stream().filter(adapter -> platform.equals(adapter.platform())).findFirst();
    }
}
