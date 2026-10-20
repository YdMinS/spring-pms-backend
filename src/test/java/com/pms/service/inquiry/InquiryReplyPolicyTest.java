package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.CustomerInquiryReply;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.ReplyCapability;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.service.CarrierCodeService;
import com.pms.service.CarrierOption;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * InquiryReplyPolicy — 유형별 가능/불가 · 사유 우선순위 · parentReplyId 선택 (FEATURE_2609_23 / 04).
 *
 * 판정이 서버 한 곳(D5)이라는 계약이 여기서만 검증된다 — 조회 응답도 전송 서비스도 같은 메서드를 부른다.
 */
class InquiryReplyPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 10, 0);

    /**
     * The real Coupang adapter without its send collaborators — the policy reads only its judgments (FEATURE_2610_23 /
     * D12) and never sends.
     */
    private static final InquiryReplyAdapter COUPANG_ADAPTER = new CoupangInquiryReplyAdapter(null, null, null);

    private final InquiryReplyPolicy policy = new InquiryReplyPolicy(List.of(COUPANG_ADAPTER), mock(CarrierCodeService.class));

    @Test
    void evaluate_unansweredProductQna_allowsReplyWithMinLengthOne() {
        ReplyCapability capability = policy.evaluate(
                inquiry(InquiryType.PRODUCT_QNA, InquiryStatus.UNANSWERED, account("wing-user")), List.of());

        assertThat(capability.canReply()).isTrue();
        assertThat(capability.reason()).isNull();
        assertThat(capability.minLength()).isEqualTo(1);
        assertThat(capability.maxLength()).isEqualTo(1000);
        assertThat(capability.once()).isTrue();
        assertThat(capability.parentReplyId()).isNull();     // 상품문의는 상위 답변 개념이 없다
    }

    @Test
    void evaluate_unansweredCallCenter_allowsReplyWithMinLengthTwoAndParentId() {
        ReplyCapability capability = policy.evaluate(
                inquiry(InquiryType.CALL_CENTER, InquiryStatus.UNANSWERED, account("wing-user")),
                List.of(reply("8100", "requestAnswer", NOW.minusHours(2))));

        assertThat(capability.canReply()).isTrue();
        assertThat(capability.minLength()).isEqualTo(2);     // 고객센터는 쿠팡이 1자를 거절한다
        assertThat(capability.parentReplyId()).isEqualTo("8100");
    }

    @Test
    void evaluate_callCenter_picksLatestRequestAnswerReplyIgnoringLocalRows() {
        // local- 임시 행(04 가 전송 직후 넣는 것)은 쿠팡에 없는 식별자라 parentAnswerId 로 쓰면 400 이다.
        ReplyCapability capability = policy.evaluate(
                inquiry(InquiryType.CALL_CENTER, InquiryStatus.UNANSWERED, account("wing-user")),
                List.of(reply("8100", "requestAnswer", NOW.minusHours(3)),
                        reply("8123", "requestAnswer", NOW.minusHours(1)),
                        reply("8130", "answered", NOW),
                        reply("local-abc", "requestAnswer", NOW.plusHours(1))));

        assertThat(capability.parentReplyId()).isEqualTo("8123");
    }

    @Test
    void evaluate_missingVendorUserId_blocksWithChannelGuidance() {
        // 1순위(어댑터 없음)에는 걸리지 않고 2순위가 나와야 한다.
        ReplyCapability capability = policy.evaluate(
                inquiry(InquiryType.PRODUCT_QNA, InquiryStatus.UNANSWERED, account(null)), List.of());

        assertThat(capability.canReply()).isFalse();
        assertThat(capability.reason()).isEqualTo("채널에 WING 사용자 ID가 없습니다. 판매자 > 채널 수정에서 입력해 주세요.");
        assertThat(capability.minLength()).isEqualTo(1);     // 제약값은 불가일 때도 그대로 내려간다
    }

    @Test
    void evaluate_unsupportedPlatform_blocksWithAccountAliasNotPlatformCode() {
        CustomerInquiry inquiry = inquiry(InquiryType.PRODUCT_QNA, InquiryStatus.UNANSWERED, account(null))
                .toBuilder()
                .platform(Platform.NAVER)
                .build();

        ReplyCapability capability = policy.evaluate(inquiry, List.of());

        assertThat(capability.canReply()).isFalse();
        // vendorUserId 도 비어 있지만 1순위가 이긴다 — 사유는 위에서부터 먼저 맞는 것 하나만.
        assertThat(capability.reason()).isEqualTo("쿠팡-메인 채널은 아직 답변 전송을 지원하지 않습니다.");
        assertThat(capability.reason()).doesNotContain("NAVER");
    }

    @Test
    void evaluate_answeredInquiry_blocksEvenWhenVendorUserIdMissing() {
        // 사유 우선순위: vendorUserId 없음(2순위)이 이미 답변됨(3순위)보다 먼저다.
        ReplyCapability blockedByVendorUser = policy.evaluate(
                inquiry(InquiryType.PRODUCT_QNA, InquiryStatus.ANSWERED, account(null)), List.of());
        assertThat(blockedByVendorUser.reason())
                .isEqualTo("채널에 WING 사용자 ID가 없습니다. 판매자 > 채널 수정에서 입력해 주세요.");

        ReplyCapability answered = policy.evaluate(
                inquiry(InquiryType.PRODUCT_QNA, InquiryStatus.ANSWERED, account("wing-user")), List.of());
        assertThat(answered.canReply()).isFalse();
        assertThat(answered.reason()).isEqualTo("이미 답변한 문의입니다.");
    }

    @Test
    void evaluate_closedOrStale_blocksAsFinished() {
        assertThat(policy.evaluate(
                inquiry(InquiryType.CALL_CENTER, InquiryStatus.CLOSED, account("wing-user")), List.of()).reason())
                .isEqualTo("종료된 문의입니다.");
        assertThat(policy.evaluate(
                inquiry(InquiryType.PRODUCT_QNA, InquiryStatus.STALE, account("wing-user")), List.of()).reason())
                .isEqualTo("종료된 문의입니다.");
    }

    @Test
    void evaluate_callCenterWithoutRequestAnswerReply_blocksAsDataDrift() {
        // §3.1 정의상 성립하지 않는 조합 — 파서·상태 정규화가 어긋났다는 신호다.
        ReplyCapability capability = policy.evaluate(
                inquiry(InquiryType.CALL_CENTER, InquiryStatus.UNANSWERED, account("wing-user")),
                List.of(reply("8100", "answered", NOW), reply("local-abc", "requestAnswer", NOW)));

        assertThat(capability.canReply()).isFalse();
        assertThat(capability.reason()).isEqualTo("판매자 답변이 요청된 문의가 아닙니다.");
    }

    // ---- FEATURE_2610_17 / D27 · D44 · D45 ----

    /** The real 11st adapter without its client — the policy test sends nothing. */
    private static final InquiryReplyAdapter ELEVENST_ADAPTER = new ElevenstInquiryReplyAdapter(null);

    @Test
    void evaluate_elevenstUrgentNoticeReRequested_canReplyWithMinLengthOne_noWingIdNeeded() {
        InquiryReplyPolicy withElevenst = new InquiryReplyPolicy(List.of(COUPANG_ADAPTER, ELEVENST_ADAPTER),
                mock(CarrierCodeService.class));
        MarketplaceAccount eleven = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(9L).build();
        CustomerInquiry urgent = inquiry(InquiryType.CALL_CENTER, InquiryStatus.UNANSWERED, eleven).toBuilder()
                .platform(Platform.ELEVENST).platformStatus("04").build();

        ReplyCapability capability = withElevenst.evaluate(urgent, List.of());

        assertThat(capability.canReply()).isTrue();
        assertThat(capability.minLength()).isEqualTo(1);
        assertThat(capability.parentReplyId()).isNull();
    }

    // ---- FEATURE_2610_17 / D68 ----

    @Test
    void evaluate_elevenstNonDelivery_givesTheNonDeliveryFormWithItsReasonsAndMax200() {
        InquiryReplyPolicy withElevenst = new InquiryReplyPolicy(List.of(ELEVENST_ADAPTER),
                mock(CarrierCodeService.class));
        CustomerInquiry report = nonDeliveryReport();

        ReplyCapability capability = withElevenst.evaluate(report, List.of());

        assertThat(capability.canReply()).isTrue();
        assertThat(capability.form()).isEqualTo(ReplyCapability.FORM_NON_DELIVERY);
        assertThat(capability.maxLength()).isEqualTo(200);
        assertThat(capability.reasons()).extracting(c -> c.code()).containsExactly("101", "102", "103", "104");
    }

    @Test
    void nonDelivery_resend_checksCarrierAndInvoice_andBuildsTheRecordLine() {
        CarrierCodeService carriers = mock(CarrierCodeService.class);
        given(carriers.validateDeliveryCompanyCode("00034", Platform.ELEVENST)).willReturn("00034");
        given(carriers.findOptions(Platform.ELEVENST)).willReturn(List.of(new CarrierOption("00034", "CJ대한통운", true)));
        InquiryReplyPolicy withElevenst = new InquiryReplyPolicy(List.of(ELEVENST_ADAPTER), carriers);

        InquiryReplyPolicy.NonDeliveryInput input = withElevenst.nonDelivery(nonDeliveryReport(), "오늘 다시 보냈습니다",
                "102", "00034", "1234-5678-9012");

        assertThat(input.invoiceNumber()).isEqualTo("123456789012");
        assertThat(input.recordText()).isEqualTo("재발송하였습니다. — 오늘 다시 보냈습니다 (재발송 CJ대한통운 123456789012)");
    }

    @Test
    void nonDelivery_otherReason_sendsNoInvoice_andUnknownReasonIs400() {
        InquiryReplyPolicy withElevenst = new InquiryReplyPolicy(List.of(ELEVENST_ADAPTER),
                mock(CarrierCodeService.class));

        InquiryReplyPolicy.NonDeliveryInput input = withElevenst.nonDelivery(nonDeliveryReport(), "통화했습니다",
                "101", "00034", "123456789012");

        assertThat(input.carrierCode()).isNull();
        assertThat(input.invoiceNumber()).isNull();
        assertThat(input.recordText()).isEqualTo("구매자와 연락하여 협의하였습니다. — 통화했습니다");
        assertThatThrownBy(() -> withElevenst.nonDelivery(nonDeliveryReport(), "x", "999", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("처리 사유를 선택하세요");
    }

    private CustomerInquiry nonDeliveryReport() {
        MarketplaceAccount eleven = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(9L).build();
        return inquiry(InquiryType.NON_DELIVERY, InquiryStatus.UNANSWERED, eleven).toBuilder()
                .platform(Platform.ELEVENST).platformStatus("미도착처리중").build();
    }

    private MarketplaceAccount account(String vendorUserId) {
        return MarketplaceAccountFixture.coupangStubBuilder(null, vendorUserId)
                .id(7L).platform(Platform.COUPANG).accountAlias("쿠팡-메인").build();
    }

    private CustomerInquiry inquiry(InquiryType type, InquiryStatus status, MarketplaceAccount account) {
        return CustomerInquiry.builder()
                .id(3L)
                .marketplaceAccount(account)
                .platform(Platform.COUPANG)
                .inquiryType(type)
                .externalInquiryId("I-1")
                .status(status)
                .platformStatus("NOANSWER")
                .inquiredAt(NOW.minusDays(1))
                .lastSyncedAt(NOW)
                .build();
    }

    private CustomerInquiryReply reply(String externalReplyId, String transferStatus, LocalDateTime repliedAt) {
        return CustomerInquiryReply.builder()
                .id(Math.abs((long) externalReplyId.hashCode()))
                .externalReplyId(externalReplyId)
                .authorRole(InquiryAuthorRole.CS_AGENT)
                .transferStatus(transferStatus)
                .content("판매자 확인 부탁드립니다.")
                .repliedAt(repliedAt)
                .build();
    }
}
