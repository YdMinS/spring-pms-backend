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
import com.pms.service.coupang.SyncWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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
    private static final String EXCHANGE_RECEIVE_CONFIRM_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/exchangeRequests/{exchangeId}/receiveConfirmation";
    private static final String EXCHANGE_REJECTION_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/exchangeRequests/{exchangeId}/rejection";
    private static final String EXCHANGE_INVOICE_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/exchangeRequests/{exchangeId}/invoices";

    /** 접수 하나에 원 배송번호(item 레벨)와 재배송 박스(group 레벨)가 함께 들어 있다 — 골라야 할 값은 후자다. */
    private static final String RESHIP_RECEIPT_JSON = """
            {"exchangeId":"40362",
             "exchangeItemDtoV1s":[{"shipmentBoxId":"111000"}],
             "deliveryInvoiceGroupDtos":[{"shipmentBoxId":"987654321"}]}
            """;

    @Mock private CoupangApiClient coupangApiClient;
    @Mock private CoupangProperties coupangProperties;
    @Mock private CarrierCodeService carrierCodeService;
    @Mock private CoupangClaimAdapter coupangClaimAdapter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CoupangClaimActionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CoupangClaimActionAdapter(
                coupangApiClient, coupangProperties, carrierCodeService, coupangClaimAdapter, objectMapper);
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

    // ── 교환 4액션 (05) ──────────────────────────────────────────────────────────

    @Test
    void availableActions_exchangeReceiptBeforeDirection_offersCollectInvoiceAndReject() {
        List<ClaimActionOption> options =
                adapter.availableActions(exchangeClaim("RECEIPT", "BeforeDirection"), Set.of());

        assertThat(options).extracting(ClaimActionOption::action).containsExactlyInAnyOrder(
                ClaimAction.EXCHANGE_COLLECT_INVOICE, ClaimAction.EXCHANGE_REJECT);
    }

    @Test
    void availableActions_exchangeProgressCompleteCollect_offersReceiveConfirmAndReshipInvoice() {
        List<ClaimActionOption> options =
                adapter.availableActions(exchangeClaim("PROGRESS", "CompleteCollect"), Set.of());

        assertThat(options).extracting(ClaimActionOption::action).containsExactlyInAnyOrder(
                ClaimAction.EXCHANGE_RECEIVE_CONFIRM, ClaimAction.EXCHANGE_RESHIP_INVOICE);
    }

    @Test
    void availableActions_exchangeWithoutCollectStatus_dropsOnlyCollectGatedActions() {
        // 회수상태가 비어 있는 것과 조건을 만족하는 것은 다르다(D3) — 송장 액션만 닫히고 나머지는 남는다.
        assertThat(adapter.availableActions(exchangeClaim("RECEIPT", null), Set.of()))
                .extracting(ClaimActionOption::action).containsExactly(ClaimAction.EXCHANGE_REJECT);
        assertThat(adapter.availableActions(exchangeClaim("PROGRESS", "SomethingNew"), Set.of()))
                .extracting(ClaimActionOption::action).containsExactly(ClaimAction.EXCHANGE_RECEIVE_CONFIRM);
        // 회수상태가 맞아도 platform_status 가 어긋나면 열리지 않는다(2축 판정).
        assertThat(adapter.availableActions(exchangeClaim("RECEIPT", "CompleteCollect"), Set.of()))
                .extracting(ClaimActionOption::action).containsExactly(ClaimAction.EXCHANGE_REJECT);
    }

    @Test
    void availableActions_exchangeClosedStatus_offersNothing() {
        assertThat(adapter.availableActions(exchangeClaim("SUCCESS", "CompleteCollect"), Set.of())).isEmpty();
        assertThat(adapter.availableActions(exchangeClaim("CANCEL", "BeforeDirection"), Set.of())).isEmpty();
        assertThat(adapter.availableActions(exchangeClaim("REJECT", null), Set.of())).isEmpty();
        assertThat(adapter.availableActions(exchangeClaim("SOMETHING_NEW", "BeforeDirection"), Set.of())).isEmpty();
    }

    @Test
    void execute_exchangeReceiveConfirm_sendsNumericExchangeIdViaPatch() throws Exception {
        given(coupangProperties.getExchangeReceiveConfirmPath()).willReturn(EXCHANGE_RECEIVE_CONFIRM_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        adapter.execute(account(), List.of(exchangeClaim("PROGRESS", "CompleteCollect")),
                new ClaimActionCommand(ClaimAction.EXCHANGE_RECEIVE_CONFIRM, null, null, null, null));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).patch(path.capture(), body.capture(), any());

        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.get("vendorId").asText()).isEqualTo("A001");
        assertThat(sent.get("exchangeId").isNumber()).isTrue();      // 문자열이면 쿠팡 400
        assertThat(sent.get("exchangeId").asLong()).isEqualTo(40362L);
        assertThat(path.getValue()).isEqualTo(
                "/v2/providers/openapi/apis/api/v4/vendors/A001/exchangeRequests/40362/receiveConfirmation");
    }

    @Test
    void execute_exchangeReject_sendsCodeFromTheChoicesList() throws Exception {
        given(coupangProperties.getExchangeRejectionPath()).willReturn(EXCHANGE_REJECTION_PATH);
        given(coupangApiClient.patch(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        ClaimActionOutcome outcome = adapter.execute(account(), List.of(exchangeClaim("RECEIPT", null)),
                new ClaimActionCommand(ClaimAction.EXCHANGE_REJECT, null, null, null, "SOLDOUT"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).patch(anyString(), body.capture(), any());
        assertThat(objectMapper.readTree(body.getValue()).get("exchangeRejectCode").asText())
                .isEqualTo("SOLDOUT");
        assertThat(outcome.succeeded()).isTrue();

        // 서버 검증과 화면 선택지가 같은 목록이라는 것 — 어댑터가 목록의 유일한 소유자다(D19).
        List<ClaimActionOption> options = adapter.availableActions(exchangeClaim("RECEIPT", null), Set.of());
        assertThat(options.get(0).choices()).extracting(ActionChoice::code)
                .containsExactly("SOLDOUT", "WITHDRAW");
    }

    @Test
    void execute_exchangeRejectWithUnknownCode_failsBeforeCallingCoupang() {
        // 경로는 정상 설정 상태로 두고(실제 운영과 동일) 값 검증만으로 끊기는지 본다.
        given(coupangProperties.getExchangeRejectionPath()).willReturn(EXCHANGE_REJECTION_PATH);

        assertThatThrownBy(() -> adapter.execute(account(), List.of(exchangeClaim("RECEIPT", null)),
                new ClaimActionCommand(ClaimAction.EXCHANGE_REJECT, null, null, null, "ETC")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(coupangApiClient, never()).patch(anyString(), anyString(), any());
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void execute_exchangeCollectInvoice_reusesReturnPathWithExchangeDeliveryType() throws Exception {
        given(coupangProperties.getReturnExchangeInvoicePath()).willReturn(INVOICE_PATH);
        given(carrierCodeService.validateDeliveryCompanyCode("CJGLS", "COUPANG")).willReturn("CJGLS");
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        adapter.execute(account(), List.of(exchangeClaim("RECEIPT", "BeforeDirection")),
                new ClaimActionCommand(ClaimAction.EXCHANGE_COLLECT_INVOICE,
                        "CJGLS", "123456789012", null, null));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(path.capture(), body.capture(), any());

        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.get("returnExchangeDeliveryType").asText()).isEqualTo("EXCHANGE");
        assertThat(sent.get("receiptId").isNumber()).isTrue();      // 교환도 receiptId 자리에 exchangeId
        assertThat(sent.get("receiptId").asLong()).isEqualTo(40362L);
        assertThat(sent.get("deliveryCompanyCode").asText()).isEqualTo("CJGLS");
        // 반품 R3 과 같은 엔드포인트다 — 경로를 따로 파면 택배사 해석·에러 매핑이 갈린다.
        assertThat(path.getValue()).isEqualTo(
                "/v2/providers/openapi/apis/api/v4/vendors/A001/return-exchange-invoices/manual");
    }

    @Test
    void execute_reshipInvoice_sendsTheNewlyQueriedBoxNotTheOriginalOne() throws Exception {
        given(coupangProperties.getExchangeInvoicePath()).willReturn(EXCHANGE_INVOICE_PATH);
        given(carrierCodeService.validateDeliveryCompanyCode("CJGLS", "COUPANG")).willReturn("CJGLS");
        given(coupangClaimAdapter.findExchangeReceipt(any(), eq("40362"), any()))
                .willReturn(Optional.of(objectMapper.readTree(RESHIP_RECEIPT_JSON)));
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        OrderClaim claim = exchangeClaim("PROGRESS", "CompleteCollect");
        adapter.execute(account(), List.of(claim),
                new ClaimActionCommand(ClaimAction.EXCHANGE_RESHIP_INVOICE,
                        "CJGLS", "123456789012", null, null));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), body.capture(), any());

        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.get("shipmentBoxId").asText()).isEqualTo("987654321");                  // 재조회로 얻은 새 박스
        assertThat(sent.get("shipmentBoxId").asText()).isNotEqualTo(claim.getExternalBoxId());  // 원 배송번호 아님
        assertThat(sent.get("shipmentBoxId").isNumber()).isTrue();                              // 문자열이면 400

        // 재조회 → 송장 순서가 뒤집히면 낡은 박스를 보낸다.
        var order = inOrder(coupangClaimAdapter, coupangApiClient);
        order.verify(coupangClaimAdapter).findExchangeReceipt(any(), anyString(), any());
        order.verify(coupangApiClient).post(anyString(), anyString(), any());
    }

    @Test
    void execute_reshipInvoiceWithoutANewBox_failsWithoutSendingTheInvoice() throws Exception {
        given(coupangProperties.getExchangeInvoicePath()).willReturn(EXCHANGE_INVOICE_PATH);
        given(carrierCodeService.validateDeliveryCompanyCode("CJGLS", "COUPANG")).willReturn("CJGLS");

        // ① 접수를 아예 못 찾음 ② 우리가 모르는 키만 있음 ③ 원 배송번호만 있음
        // ③ 은 "찾은 것"이 아니다 — 폴백하면 200 이 돌아오고 엉뚱한 박스에 송장이 붙는다.
        List<Optional<JsonNode>> responses = List.of(
                Optional.empty(),
                Optional.of(objectMapper.readTree("{\"exchangeId\":\"40362\",\"somethingElse\":[{\"x\":1}]}")),
                Optional.of(objectMapper.readTree(
                        "{\"exchangeId\":\"40362\",\"deliveryInvoiceGroupDtos\":[{\"shipmentBoxId\":\"111000\"}]}")));

        for (Optional<JsonNode> response : responses) {
            given(coupangClaimAdapter.findExchangeReceipt(any(), eq("40362"), any())).willReturn(response);

            assertThatThrownBy(() -> adapter.execute(account(),
                    List.of(exchangeClaim("PROGRESS", "CompleteCollect")),
                    new ClaimActionCommand(ClaimAction.EXCHANGE_RESHIP_INVOICE,
                            "CJGLS", "123456789012", null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void execute_reshipInvoice_usesGoodsDeliveryCodeAndAOneDayWindowOnTheReceiptDate() throws Exception {
        given(coupangProperties.getExchangeInvoicePath()).willReturn(EXCHANGE_INVOICE_PATH);
        given(carrierCodeService.validateDeliveryCompanyCode("CJGLS", "COUPANG")).willReturn("CJGLS");
        given(coupangClaimAdapter.findExchangeReceipt(any(), eq("40362"), any()))
                .willReturn(Optional.of(objectMapper.readTree(RESHIP_RECEIPT_JSON)));
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        adapter.execute(account(), List.of(exchangeClaim("PROGRESS", "CompleteCollect")),
                new ClaimActionCommand(ClaimAction.EXCHANGE_RESHIP_INVOICE,
                        "CJGLS", "123456789012", null, null));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), body.capture(), any());
        JsonNode sent = objectMapper.readTree(body.getValue());
        // 이 액션만 필드명이 goodsDeliveryCode 다(문서 그대로) — deliveryCompanyCode 로 보내면 400.
        assertThat(sent.has("goodsDeliveryCode")).isTrue();
        assertThat(sent.has("deliveryCompanyCode")).isFalse();
        assertThat(sent.get("goodsDeliveryCode").asText()).isEqualTo("CJGLS");

        // 재조회 창 = 접수일 하루. "최근 7일"이 아니다 — 접수일은 이미 과거일 수 있다.
        ArgumentCaptor<SyncWindow> window = ArgumentCaptor.forClass(SyncWindow.class);
        verify(coupangClaimAdapter).findExchangeReceipt(any(), anyString(), window.capture());
        assertThat(window.getValue().from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(window.getValue().to()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    private MarketplaceAccount account() {
        return MarketplaceAccount.builder().id(1L).platform("COUPANG").vendorId("A001").build();
    }

    /** 교환 접수 1건 — 원 배송번호(externalBoxId)가 재발송 박스와 다르다는 것이 X3 의 전제다. */
    private OrderClaim exchangeClaim(String platformStatus, String collectStatus) {
        return OrderClaim.builder()
                .id(9L)
                .marketplaceAccount(account())
                .platform("COUPANG")
                .claimType(ClaimType.EXCHANGE)
                .externalClaimId("40362")
                .externalOrderId("O-9")
                .externalBoxId("111000")
                .externalItemId("V-9")
                .quantity(1)
                .platformStatus(platformStatus)
                .collectStatus(collectStatus)
                .receivedAt(LocalDateTime.of(2026, 9, 1, 10, 20, 30))
                .build();
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
