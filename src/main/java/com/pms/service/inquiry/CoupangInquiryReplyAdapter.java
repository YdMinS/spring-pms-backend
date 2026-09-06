package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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

    private static final String PLATFORM_COUPANG = "COUPANG";

    private final CoupangApiClient coupangApiClient;
    private final CoupangProperties coupangProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return PLATFORM_COUPANG;
    }

    @Override
    public void reply(MarketplaceAccount account, CustomerInquiry inquiry, ReplyCommand command) {
        boolean callCenter = inquiry.getInquiryType() == InquiryType.CALL_CENTER;
        String path = (callCenter
                ? coupangProperties.getCallCenterInquiryReplyPath()
                : coupangProperties.getOnlineInquiryReplyPath())
                .replace("{vendorId}", account.getVendorId())
                .replace("{inquiryId}", inquiry.getExternalInquiryId());

        Map<String, Object> body = callCenter
                ? callCenterBody(account, inquiry, command)
                : productQnaBody(account, command);

        parse(coupangApiClient.post(path, json(body), account), inquiry);
    }

    /** 상품문의 바디 — {@code replyBy} 는 WING 사용자 ID 다(D18, 계정에서 온다). */
    private Map<String, Object> productQnaBody(MarketplaceAccount account, ReplyCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", command.content());
        body.put("vendorId", account.getVendorId());
        body.put("replyBy", account.getVendorUserId());
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
        body.put("vendorId", account.getVendorId());
        body.put("inquiryId", inquiry.getExternalInquiryId());
        body.put("content", command.content());
        body.put("replyBy", account.getVendorUserId());
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
