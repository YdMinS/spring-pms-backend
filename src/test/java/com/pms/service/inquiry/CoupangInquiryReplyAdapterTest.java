package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CoupangInquiryReplyAdapter — 유형별 경로·바디·응답 판정 (FEATURE_2609_23 / 04 Step 3).
 *
 * ⚠️ 조회는 v5, 답변은 v4 다. 경로 단언이 그 사실을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class CoupangInquiryReplyAdapterTest {

    @Mock private CoupangApiClient coupangApiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CoupangProperties coupangProperties = new CoupangProperties();

    private CoupangInquiryReplyAdapter adapter() {
        return new CoupangInquiryReplyAdapter(coupangApiClient, coupangProperties, objectMapper);
    }

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("A00012345", "wing-user")
            .id(7L).platform(Platform.COUPANG).build();

    @Test
    void reply_productQna_postsToV4OnlineInquiriesWithReplyBy() throws Exception {
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("{\"code\":\"200\"}");

        adapter().reply(account, inquiry(InquiryType.PRODUCT_QNA, "5001"),
                new InquiryReplyAdapter.ReplyCommand("내일 출고 예정입니다.", null));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(path.capture(), body.capture(), eq(account));

        assertThat(path.getValue())
                .isEqualTo("/v2/providers/openapi/apis/api/v4/vendors/A00012345/onlineInquiries/5001/replies");
        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.path("content").asText()).isEqualTo("내일 출고 예정입니다.");
        assertThat(sent.path("vendorId").asText()).isEqualTo("A00012345");
        assertThat(sent.path("replyBy").asText()).isEqualTo("wing-user");
        assertThat(sent.has("parentAnswerId")).isFalse();
    }

    @Test
    void reply_callCenter_postsParentAnswerIdAsNumber() throws Exception {
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("{\"code\":\"200\"}");

        adapter().reply(account, inquiry(InquiryType.CALL_CENTER, "7777"),
                new InquiryReplyAdapter.ReplyCommand("확인 후 회신드리겠습니다.", "8123"));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(path.capture(), body.capture(), eq(account));

        assertThat(path.getValue())
                .isEqualTo("/v2/providers/openapi/apis/api/v4/vendors/A00012345/callCenterInquiries/7777/replies");
        JsonNode sent = objectMapper.readTree(body.getValue());
        // 🔴 문자열로 보내면 쿠팡 400 — 숫자 노드여야 한다.
        assertThat(sent.path("parentAnswerId").isNumber()).isTrue();
        assertThat(sent.path("parentAnswerId").asLong()).isEqualTo(8123L);
        assertThat(sent.path("inquiryId").asText()).isEqualTo("7777");
        assertThat(sent.path("replyBy").asText()).isEqualTo("wing-user");
    }

    @Test
    void reply_callCenterWithNonNumericParentId_failsWithoutCallingCoupang() {
        assertThatThrownBy(() -> adapter().reply(account, inquiry(InquiryType.CALL_CENTER, "7777"),
                new InquiryReplyAdapter.ReplyCommand("확인했습니다.", "local-abc")))
                .isInstanceOf(InquiryReplyFailedException.class)
                .hasMessage("답변 대상 식별자가 올바르지 않습니다.");

        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void reply_bodyWithNewlinesAndQuotes_isJsonEscaped() throws Exception {
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("{\"code\":\"200\"}");
        String content = "안녕하세요.\n\"바로\" 발송\\예정입니다.";

        adapter().reply(account, inquiry(InquiryType.PRODUCT_QNA, "5001"),
                new InquiryReplyAdapter.ReplyCommand(content, null));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), body.capture(), any());
        // 문자열 연결로 바디를 만들면 여기서 깨진다 — 왕복이 원문과 같아야 한다.
        assertThat(objectMapper.readTree(body.getValue()).path("content").asText()).isEqualTo(content);
    }

    @Test
    void reply_nonSuccessCode_throwsWithCoupangMessage() {
        given(coupangApiClient.post(anyString(), anyString(), any()))
                .willReturn("{\"code\":\"400\",\"message\":\"이미 답변이 등록된 문의입니다.\"}");

        assertThatThrownBy(() -> adapter().reply(account, inquiry(InquiryType.PRODUCT_QNA, "5001"),
                new InquiryReplyAdapter.ReplyCommand("내일 출고 예정입니다.", null)))
                .isInstanceOf(InquiryReplyFailedException.class)
                .hasMessage("이미 답변이 등록된 문의입니다.");
    }

    @Test
    void reply_unparsableResponse_isTreatedAsFailure() {
        // 파싱 불가 = 성공으로 볼 근거가 없다.
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("<html>gateway error</html>");

        assertThatThrownBy(() -> adapter().reply(account, inquiry(InquiryType.PRODUCT_QNA, "5001"),
                new InquiryReplyAdapter.ReplyCommand("내일 출고 예정입니다.", null)))
                .isInstanceOf(InquiryReplyFailedException.class);
    }

    private CustomerInquiry inquiry(InquiryType type, String externalInquiryId) {
        return CustomerInquiry.builder()
                .id(3L)
                .marketplaceAccount(account)
                .platform(Platform.COUPANG)
                .inquiryType(type)
                .externalInquiryId(externalInquiryId)
                .status(InquiryStatus.UNANSWERED)
                .platformStatus("NOANSWER")
                .inquiredAt(LocalDateTime.of(2026, 9, 5, 10, 0))
                .lastSyncedAt(LocalDateTime.of(2026, 9, 6, 10, 0))
                .build();
    }
}
