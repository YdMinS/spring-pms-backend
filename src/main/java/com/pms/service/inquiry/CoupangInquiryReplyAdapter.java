package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 쿠팡 고객문의 답변 전송 어댑터 (FEATURE_2609_23 / 04 Step 3).
 *
 * 유형에 따라 <b>두 경로</b>로 갈린다 — 상품문의 {@code onlineInquiries/{id}/replies},
 * 고객센터 {@code callCenterInquiries/{id}/replies}. 바디 구성도 다르다(고객센터만
 * {@code parentAnswerId} 가 필수).
 *
 * <p>⚠️ 조회는 <b>v5</b>, 답변은 <b>v4</b> 다. 버전을 맞추려 하지 말 것 — 쿠팡이 그렇게 나눠 놓았다
 * (반품이 겪은 "조회 v6 vs 액션 v4" 와 같은 형태).
 * <p>⚠️ {@code parentAnswerId} 는 <b>숫자</b>로 보낸다 — 문자열이면 400 이다.
 * <p>⚠️ 바디를 문자열 연결로 만들지 말 것 — 본문의 {@code \n}·{@code \\}·{@code "} 가 JSON 을 깨뜨린다.
 * {@link ObjectMapper} 로만 직렬화한다.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 없음 — 외부 HTTP 다. DB 는 아예 건드리지 않는다
 * (로컬 반영은 {@link InquiryReplyRecorder} 담당).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangInquiryReplyAdapter implements InquiryReplyAdapter {

    /** 답변 전송(04)이 성공 직후 넣는 로컬 임시 행의 식별자 접두 — {@code InquiryUpserter} 와 같은 값이다. */
    private static final String LOCAL_REPLY_PREFIX = "local-";

    /** 🔴 고객센터에서 "이제 판매자가 답할 차례" 를 뜻하는 쿠팡 원문. {@code parentAnswerId} 의 출처다. */
    private static final String TRANSFER_REQUEST_ANSWER = "requestAnswer";

    /** 상품문의는 한 글자도 허용, 고객센터는 쿠팡이 2자 미만을 거절한다 — 유형별로 다르다. */
    private static final int MIN_LENGTH_PRODUCT_QNA = 1;
    private static final int MIN_LENGTH_CALL_CENTER = 2;

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    @Override
    public int minLength(InquiryType type) {
        return (type == InquiryType.CALL_CENTER) ? MIN_LENGTH_CALL_CENTER : MIN_LENGTH_PRODUCT_QNA;
    }

    /**
     * The WING user id ({@code replyBy} source, D18 — owned by the Coupang credential since 2609_26) must be set. A
     * missing account or credential folds to "no source" without throwing — the same block as the send path's 400.
     */
    @Override
    public String accountBlockReason(MarketplaceAccount account) {
        String wingUserId = (account == null || account.getPlatform() != platform()
                || account.getCoupangCredential() == null)
                ? null : CoupangCredentials.of(account).getVendorUserId();
        if (wingUserId == null || wingUserId.isBlank()) {
            return "채널에 WING 사용자 ID가 없습니다. 판매자 > 채널 수정에서 입력해 주세요.";
        }
        return null;
    }

    /**
     * Drift guard — by §3.1 an UNANSWERED Coupang call-center inquiry always has a requestAnswer reply. Hitting this
     * means the parser (01) or the status normalization drifted.
     */
    @Override
    public String threadBlockReason(CustomerInquiry inquiry, List<CustomerInquiryReply> replies) {
        if (inquiry.getInquiryType() == InquiryType.CALL_CENTER && requestAnswerReply(replies).isEmpty()) {
            log.warn("Call-center inquiry is UNANSWERED but has no requestAnswer reply — parser or status "
                    + "normalization drifted: inquiry={} platformStatus={}",
                    inquiry.getId(), inquiry.getPlatformStatus());
            return "판매자 답변이 요청된 문의가 아닙니다.";
        }
        return null;
    }

    /** Parent reply id of a call-center inquiry only; product Q&A is always null. */
    @Override
    public String parentReplyId(CustomerInquiry inquiry, List<CustomerInquiryReply> replies) {
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

    @Override
    public void reply(MarketplaceAccount account, CustomerInquiry inquiry, ReplyCommand command) {
        boolean callCenter = inquiry.getInquiryType() == InquiryType.CALL_CENTER;
        String path = (callCenter
                ? coupangProperties.getCallCenterInquiryReplyPath()
                : coupangProperties.getOnlineInquiryReplyPath())
                .replace("{vendorId}", CoupangCredentials.of(account).getVendorId())
                .replace("{inquiryId}", inquiry.getExternalInquiryId());

        Map<String, Object> body = callCenter
                ? callCenterBody(account, inquiry, command)
                : productQnaBody(account, command);

        parse(coupangApiClient.post(path, json(body), account), inquiry);
    }

    /** 상품문의 바디 — {@code replyBy} 는 WING 사용자 ID 다(D18, 계정에서 온다). */
    private Map<String, Object> productQnaBody(MarketplaceAccount account, ReplyCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        var cred = CoupangCredentials.of(account);
        body.put("content", command.content());
        body.put("vendorId", cred.getVendorId());
        body.put("replyBy", cred.getVendorUserId());
        return body;
    }

    /**
     * 고객센터 바디 — {@code parentAnswerId} 가 필수이고 <b>숫자</b>여야 한다.
     *
     * 숫자가 아니면 전송하지 않는다: {@code local-} 임시 ID 가 흘러든 경우이며(정책이
     * {@code transfer_status} 로 고르므로 정상 경로에서는 나오지 않는다) 그대로 보내면 쿠팡 400 이다.
     */
    private Map<String, Object> callCenterBody(MarketplaceAccount account, CustomerInquiry inquiry,
                                               ReplyCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        var cred = CoupangCredentials.of(account);
        body.put("vendorId", cred.getVendorId());
        body.put("inquiryId", inquiry.getExternalInquiryId());
        body.put("content", command.content());
        body.put("replyBy", cred.getVendorUserId());
        body.put("parentAnswerId", parentAnswerId(command.parentReplyId()));
        return body;
    }

    private long parentAnswerId(String parentReplyId) {
        try {
            return Long.parseLong(parentReplyId.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new InquiryReplyFailedException("답변 대상 식별자가 올바르지 않습니다.");
        }
    }

    private String json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("고객문의 답변 요청 직렬화 실패", e);
        }
    }

    /**
     * HTTP 200 이어도 바디 {@code code} 가 200 이 아닐 수 있다 → <b>바디를 읽고 판정</b>한다
     * ({@code CoupangClaimActionAdapter} 와 같은 방식). 실패면 쿠팡이 준 {@code message} 를 그대로 담는다.
     */
    private void parse(String response, CustomerInquiry inquiry) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response == null ? "" : response);
        } catch (Exception e) {
            // 파싱 불가 = 성공으로 볼 근거가 없다. 원문을 잘라 로그에 남긴다.
            log.warn("고객문의 답변 응답 파싱 실패: inquiry={} response={}", inquiry.getId(), truncate(response));
            throw new InquiryReplyFailedException("답변 전송 응답을 해석하지 못했습니다.");
        }
        String code = root.path("code").asText("");
        String message = root.path("message").asText("");
        if ("200".equals(code) || "SUCCESS".equalsIgnoreCase(code)) {
            return;
        }
        // 알려진 400 사유(잘못된 replyBy · 삭제된 문의 · 중복 답변 · 빈 본문 · 길이 초과 · 상담 종료됨)를
        // 구분하지 않고 원문을 그대로 올린다 — 사용자가 화면에서 읽고 판단하는 유일한 단서다.
        log.warn("고객문의 답변 거절: inquiry={} type={} code={} message={}",
                inquiry.getId(), inquiry.getInquiryType(), code, message);
        throw new InquiryReplyFailedException(message.isBlank() ? "답변 전송에 실패했습니다." : message);
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
