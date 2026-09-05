package com.pms.service.claim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.ClaimAction;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;
import com.pms.service.CarrierCodeService;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * CoupangClaimActionAdapter — 상태 화이트리스트(D2·D3)와 바디 조립.
 *
 * 쿠팡 클라이언트·택배사 코드 서비스는 @Mock, ObjectMapper 는 실제 인스턴스(보낸 바디를 문자열로
 * 캡처해 검증하기 위해). 서비스 계층 가드는 ClaimActionServiceImplTest 담당 — 여기서 재검증하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class CoupangClaimActionAdapterTest {

    private static final String RECEIVE_CONFIRM_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/returnRequests/{receiptId}/receiveConfirmation";
    private static final String APPROVAL_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/returnRequests/{receiptId}/approval";
    private static final String INVOICE_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/return-exchange-invoices/manual";

    @Mock private CoupangApiClient coupangApiClient;
    @Mock private CoupangProperties coupangProperties;
    @Mock private CarrierCodeService carrierCodeService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CoupangClaimActionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CoupangClaimActionAdapter(
                coupangApiClient, coupangProperties, carrierCodeService, objectMapper);
    }

    @Test
    void availableActions_returnsUnchecked_offersReceiveConfirmAndCollectInvoice() {
        List<ClaimActionOption> options =
                adapter.availableActions(claim(1L, "RETURNS_UNCHECKED", 1), Set.of());

        assertThat(options).extracting(ClaimActionOption::action).containsExactlyInAnyOrder(
                ClaimAction.RETURN_RECEIVE_CONFIRM, ClaimAction.RETURN_COLLECT_INVOICE);
        // 반품 3액션은 값 선택이 없으므로 choices 는 비어 있지만 필드는 지금 존재해야 한다(05 대비).
        assertThat(options).allSatisfy(option -> assertThat(option.choices()).isEmpty());
        assertThat(options).noneMatch(ClaimActionOption::irreversible);
    }

    @Test
    void availableActions_vendorWarehouseConfirm_offersApproveAsIrreversible() {
        List<ClaimActionOption> options =
                adapter.availableActions(claim(1L, "VENDOR_WAREHOUSE_CONFIRM", 1), Set.of());

        assertThat(options).hasSize(1);
        assertThat(options.get(0).action()).isEqualTo(ClaimAction.RETURN_APPROVE);
        assertThat(options.get(0).irreversible()).isTrue();      // UI 2단 확인의 근거(D10)
        assertThat(options.get(0).requires()).isEqualTo(ClaimAction.Requires.NONE);
    }

    @Test
    void availableActions_completedStatus_offersNothing() {
        assertThat(adapter.availableActions(claim(1L, "RETURNS_COMPLETED", 1), Set.of())).isEmpty();
    }

    @Test
    void availableActions_unknownOrShortCodeStatus_offersNothing() {
        // D3 — 단축 코드(01 이 하위호환으로 읽기만 남긴 값)와 미지의 값에는 되돌릴 수 없는 쓰기를 열지 않는다.
        assertThat(adapter.availableActions(claim(1L, "UC", 1), Set.of())).isEmpty();
        assertThat(adapter.availableActions(claim(1L, "SOMETHING_NEW", 1), Set.of())).isEmpty();
        assertThat(adapter.availableActions(claim(1L, null, 1), Set.of())).isEmpty();
    }

    @Test
    void availableActions_alreadySucceeded_dropsThatAction() {
        List<ClaimActionOption> options = adapter.availableActions(
                claim(1L, "RETURNS_UNCHECKED", 1), Set.of(ClaimAction.RETURN_RECEIVE_CONFIRM));

        assertThat(options).extracting(ClaimActionOption::action)
                .containsExactly(ClaimAction.RETURN_COLLECT_INVOICE);
    }

    @Test
    void execute_approve_sendsSiblingQuantitySumAsNumericReceiptId() throws Exception {
        given(coupangProperties.getReturnApprovalPath()).willReturn(APPROVAL_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        // 같은 접수의 형제 라인 3건(수량 1+2+1)에서 승인
        List<OrderClaim> siblings = List.of(
                claim(1L, "VENDOR_WAREHOUSE_CONFIRM", 1),
                claim(2L, "VENDOR_WAREHOUSE_CONFIRM", 2),
                claim(3L, "VENDOR_WAREHOUSE_CONFIRM", 1));

        ClaimActionOutcome outcome = adapter.execute(account(), siblings,
                new ClaimActionCommand(ClaimAction.RETURN_APPROVE, null, null, null, null));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).patch(path.capture(), body.capture(), any());

        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.get("cancelCount").asInt()).isEqualTo(4);   // 클릭한 라인의 1 이 아니다(D6)
        assertThat(sent.get("receiptId").isNumber()).isTrue();      // 문자열이면 쿠팡 400
        assertThat(sent.get("receiptId").asLong()).isEqualTo(777L);
        assertThat(sent.get("vendorId").asText()).isEqualTo("A001");
        assertThat(path.getValue()).isEqualTo(
                "/v2/providers/openapi/apis/api/v4/vendors/A001/returnRequests/777/approval");
        assertThat(outcome.succeeded()).isTrue();
    }

    @Test
    void execute_collectInvoice_sendsReturnDeliveryTypeAndResolvedCarrierCode() throws Exception {
        given(coupangProperties.getReturnExchangeInvoicePath()).willReturn(INVOICE_PATH);
        given(carrierCodeService.validateDeliveryCompanyCode("CJGLS", "COUPANG")).willReturn("CJGLS");
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        adapter.execute(account(), List.of(claim(1L, "RETURNS_UNCHECKED", 1)),
                new ClaimActionCommand(ClaimAction.RETURN_COLLECT_INVOICE,
                        "CJGLS", "123456789012", null, null));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), body.capture(), any());

        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.get("returnExchangeDeliveryType").asText()).isEqualTo("RETURN");
        assertThat(sent.get("deliveryCompanyCode").asText()).isEqualTo("CJGLS");
        assertThat(sent.get("invoiceNumber").asText()).isEqualTo("123456789012");
        assertThat(sent.get("receiptId").isNumber()).isTrue();
        assertThat(sent.has("regNumber")).isFalse();               // 선택 항목은 없으면 키 자체를 뺀다
    }

    @Test
    void execute_receiveConfirm_usesPatchNotPut() throws Exception {
        given(coupangProperties.getReturnReceiveConfirmPath()).willReturn(RECEIVE_CONFIRM_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any()))
                .willReturn("{\"code\":200,\"message\":\"OK\"}");

        ClaimActionOutcome outcome = adapter.execute(account(), List.of(claim(1L, "RETURNS_UNCHECKED", 1)),
                new ClaimActionCommand(ClaimAction.RETURN_RECEIVE_CONFIRM, null, null, null, null));

        // put() 으로 우회하면 서명은 통과하고 쿠팡이 405 를 준다(D11).
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).patch(anyString(), body.capture(), any());
        assertThat(objectMapper.readTree(body.getValue()).has("cancelCount")).isFalse();
        assertThat(outcome.resultMessage()).isEqualTo("OK");
    }

    @Test
    void execute_coupangBodyReportsFailure_returnsRawCodeAndMessage() {
        given(coupangProperties.getReturnReceiveConfirmPath()).willReturn(RECEIVE_CONFIRM_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any()))
                .willReturn("{\"code\":400,\"message\":\"이미 등록된 운송장입니다\"}");

        ClaimActionOutcome outcome = adapter.execute(account(), List.of(claim(1L, "RETURNS_UNCHECKED", 1)),
                new ClaimActionCommand(ClaimAction.RETURN_RECEIVE_CONFIRM, null, null, null, null));

        // HTTP 200 이어도 바디 code 로 판정하고, 원문은 번역 없이 그대로 실어야 한다(D15).
        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.resultCode()).isEqualTo("400");
        assertThat(outcome.resultMessage()).isEqualTo("이미 등록된 운송장입니다");
    }

    @Test
    void execute_exchangeAction_throwsUnsupported() {
        assertThatThrownBy(() -> adapter.execute(account(), List.of(claim(1L, "RETURNS_UNCHECKED", 1)),
                new ClaimActionCommand(ClaimAction.EXCHANGE_RECEIVE_CONFIRM, null, null, null, null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private MarketplaceAccount account() {
        return MarketplaceAccount.builder().id(1L).platform("COUPANG").vendorId("A001").build();
    }

    private OrderClaim claim(Long id, String platformStatus, int quantity) {
        return OrderClaim.builder()
                .id(id)
                .marketplaceAccount(account())
                .platform("COUPANG")
                .claimType(ClaimType.RETURN)
                .externalClaimId("777")
                .externalOrderId("O-1")
                .externalItemId("V-" + id)
                .quantity(quantity)
                .platformStatus(platformStatus)
                .build();
    }
}
