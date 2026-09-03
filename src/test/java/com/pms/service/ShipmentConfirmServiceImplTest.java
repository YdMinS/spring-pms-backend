package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.domain.Seller;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ShipmentConfirmServiceImpl 전개·그룹핑·응답집계 테스트.
 *
 * CoupangApiClient·OrderItemRepository·CarrierCodeService·CoupangProperties 는 @Mock,
 * ObjectMapper 는 실제 인스턴스(요청 바디 직렬화/응답 파싱을 실제로 검증).
 */
@ExtendWith(MockitoExtension.class)
class ShipmentConfirmServiceImplTest {

    private static final String INVOICES_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/invoices";
    private static final String UPDATE_INVOICES_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/updateInvoices";
    private static final String ORDER_BY_ID_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/{orderId}/ordersheets";

    @Mock
    private CoupangApiClient coupangApiClient;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private CarrierCodeService carrierCodeService;
    @Mock
    private CoupangProperties coupangProperties;
    @Mock
    private MarketplaceAccountRepository marketplaceAccountRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ShipmentConfirmServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShipmentConfirmServiceImpl(
                coupangApiClient, coupangProperties, orderItemRepository,
                marketplaceAccountRepository, carrierCodeService, objectMapper);
    }

    @Test
    void confirm_happy_합포장전개() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        OrderItem l1 = line(account, "302012345678", "4000019469460", "3823839899");
        OrderItem l2 = line(account, "302012345678", "4000019469460", "3823839900");
        given(orderItemRepository.findByExternalOrderId("4000019469460")).willReturn(List.of(l1, l2));
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("302012345678", "302012345678"));

        // 주문번호는 숫자셀(지수표기 파싱 검증), 운송장번호는 문자열.
        MockMultipartFile file = xlsx(new Object[][]{{4000019469460L, "123456789"}});

        ShipmentConfirmResult result = service.confirm(file);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), bodyCaptor.capture(), eq(account));
        JsonNode dtos = objectMapper.readTree(bodyCaptor.getValue()).get("orderSheetInvoiceApplyDtos");
        assertThat(dtos).hasSize(2);
        // 합포장: shipmentBoxId·invoiceNumber 동일, vendorItemId 상이.
        assertThat(dtos.get(0).get("vendorItemId").asLong()).isNotEqualTo(dtos.get(1).get("vendorItemId").asLong());
        assertThat(dtos.get(0).get("shipmentBoxId").asLong()).isEqualTo(dtos.get(1).get("shipmentBoxId").asLong());
        assertThat(dtos.get(0).get("invoiceNumber").asText()).isEqualTo("123456789");
        assertThat(dtos.get(1).get("invoiceNumber").asText()).isEqualTo("123456789");
        assertThat(dtos.get(0).get("splitShipping").asBoolean()).isFalse();
        assertThat(dtos.get(0).get("deliveryCompanyCode").asText()).isEqualTo("CJGLS");

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.unmatched()).isEmpty();
        assertThat(result.matchedOrders()).isEqualTo(1);
    }

    @Test
    void confirm_미매칭() throws Exception {
        given(orderItemRepository.findByExternalOrderId("9999")).willReturn(List.of());

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"9999", "123"}}));

        assertThat(result.unmatched()).containsExactly("9999");
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void confirm_계정별그룹핑() throws Exception {
        MarketplaceAccount account1 = account(1L, "COUPANG", "A001");
        MarketplaceAccount account2 = account(2L, "COUPANG", "B002");
        given(orderItemRepository.findByExternalOrderId("1001")).willReturn(List.of(line(account1, "9001", "1001", "8001")));
        given(orderItemRepository.findByExternalOrderId("1002")).willReturn(List.of(line(account1, "9002", "1002", "8002")));
        given(orderItemRepository.findByExternalOrderId("1003")).willReturn(List.of(line(account2, "9003", "1003", "8003")));
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("9001"));

        service.confirm(xlsx(new Object[][]{{"1001", "i1"}, {"1002", "i2"}, {"1003", "i3"}}));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        // 판매자별 분리: 계정 2개 → POST 2회 (같은 계정 다주문은 1 POST 로 합산).
        verify(coupangApiClient, times(2)).post(pathCaptor.capture(), bodyCaptor.capture(), any());
        // 계정별 path(vendorId 치환) 상이.
        assertThat(pathCaptor.getAllValues()).anySatisfy(p -> assertThat(p).contains("A001"));
        assertThat(pathCaptor.getAllValues()).anySatisfy(p -> assertThat(p).contains("B002"));
        // account1 배치에 A·B 라인 합산(2 dto), account2 는 1 dto.
        List<Integer> dtoSizes = bodyCaptor.getAllValues().stream()
                .map(this::dtoCount).sorted().toList();
        assertThat(dtoSizes).containsExactly(1, 2);
    }

    @Test
    void confirm_비쿠팡_배제() throws Exception {
        MarketplaceAccount naver = account(1L, "NAVER", "N001");
        given(orderItemRepository.findByExternalOrderId("4000")).willReturn(List.of(line(naver, "302", "4000", "3823")));

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        assertThat(result.unmatched()).contains("4000");
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
        // 플랫폼 가드 잠금: resolve 도 미호출.
        verify(carrierCodeService, never()).resolveDeliveryCompanyCode(anyString());
        // 비-COUPANG 은 폴백 대상이 아니다 — 네이버 주문을 쿠팡에 조회하면 안 된다.
        verify(coupangApiClient, never()).get(anyString(), anyString(), any());
    }

    @Test
    void confirm_부분실패() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000")).willReturn(List.of(line(account, "302", "4000", "5001")));
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responsePartialFail());

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).shipmentBoxId()).isEqualTo("302");
        assertThat(result.failed().get(0).resultCode()).isEqualTo("DUPLICATE_INVOICE_NUMBER");
        assertThat(result.succeeded()).isZero();
    }

    @Test
    void confirm_공백행스킵() throws Exception {
        // 유효행 1 + 운송장번호 공백행 1 → totalRows 는 1 (공백행 제외).
        given(orderItemRepository.findByExternalOrderId("4000")).willReturn(List.of());

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}, {"5000", ""}}));

        assertThat(result.totalRows()).isEqualTo(1);
    }

    // --- 폴백 (PLAN D16): DB 미매칭 주문을 쿠팡 단건 조회로 확정 ---

    @Test
    void confirm_DB미매칭이면_쿠팡단건조회로_폴백해_송장업로드() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000019469460")).willReturn(List.of());
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(account));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(ORDER_BY_ID_PATH);
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(singleOrderTwoLines());
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any()))
                .willReturn(responseAllSuccess("302012345678", "302012345678"));

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{4000019469460L, "123456789"}}));

        // 조회 경로에 vendorId·orderId 가 치환된다.
        ArgumentCaptor<String> getPath = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).get(getPath.capture(), eq(""), eq(account));
        assertThat(getPath.getValue()).contains("A001").contains("4000019469460");

        // 합포장 전량 전송: 박스의 vendorItemId 2개가 모두 dto 로 나간다.
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), bodyCaptor.capture(), eq(account));
        JsonNode dtos = objectMapper.readTree(bodyCaptor.getValue()).get("orderSheetInvoiceApplyDtos");
        assertThat(dtos).hasSize(2);
        assertThat(List.of(dtos.get(0).get("vendorItemId").asLong(), dtos.get(1).get("vendorItemId").asLong()))
                .containsExactlyInAnyOrder(3823839899L, 3823839900L);
        assertThat(dtos.get(0).get("invoiceNumber").asText()).isEqualTo("123456789");

        assertThat(result.unmatched()).isEmpty();
        assertThat(result.matchedOrders()).isEqualTo(1);
    }

    @Test
    void confirm_폴백조회도실패하면_미매칭유지하고_POST안함() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000")).willReturn(List.of());
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(account));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(ORDER_BY_ID_PATH);
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willThrow(new RestClientException("504 Gateway Timeout"));

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        assertThat(result.unmatched()).containsExactly("4000");
        assertThat(result.matchedOrders()).isZero();
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void confirm_폴백응답의_전량취소라인은_제외() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000")).willReturn(List.of());
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(account));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(ORDER_BY_ID_PATH);
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(singleOrderOneCancelledLine());
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("302"));

        service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), bodyCaptor.capture(), any());
        JsonNode dtos = objectMapper.readTree(bodyCaptor.getValue()).get("orderSheetInvoiceApplyDtos");
        assertThat(dtos).hasSize(1);                                   // 전량 취소 라인 제외
        assertThat(dtos.get(0).get("vendorItemId").asLong()).isEqualTo(5002L);
    }

    // --- 상태 필터 (PLAN 2609_07): 이미 발송된 라인 제외 + 성공 박스 write-back ---

    @Test
    void confirm_DEPARTURE라인은_조회없이_스킵() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000"))
                .willReturn(List.of(line(account, "302", "4000", "5001", "DEPARTURE")));

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        // 쿠팡 호출 0회: 전송도, 상태 확인 조회도 하지 않는다.
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
        verify(coupangApiClient, never()).get(anyString(), anyString(), any());
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).orderId()).isEqualTo("4000");
        assertThat(result.skipped().get(0).status()).isEqualTo("DEPARTURE");
        assertThat(result.matchedOrders()).isZero();
        assertThat(result.unmatched()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void confirm_박스별상태혼재시_미발송박스만전송() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000")).willReturn(List.of(
                line(account, "9001", "4000", "5001", "INSTRUCT"),
                line(account, "9002", "4000", "5002", "DEPARTURE")));
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("9001"));

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), bodyCaptor.capture(), any());
        JsonNode dtos = objectMapper.readTree(bodyCaptor.getValue()).get("orderSheetInvoiceApplyDtos");
        assertThat(dtos).hasSize(1);                                   // 미발송 박스만
        assertThat(dtos.get(0).get("shipmentBoxId").asLong()).isEqualTo(9001L);
        // 스킵 박스는 별도 보고하지 않는다(이미 발송된 박스라 알릴 게 없다).
        assertThat(result.skipped()).isEmpty();
        assertThat(result.matchedOrders()).isEqualTo(1);
    }

    @Test
    void confirm_로컬ACCEPT라인도_조회없이_전송() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000"))
                .willReturn(List.of(line(account, "302", "4000", "5001", "ACCEPT")));
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("302"));

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        verify(coupangApiClient).post(anyString(), anyString(), any());
        // 승격 조회 없음: 로컬 ACCEPT 는 동기화 지연일 뿐이라 그냥 전송한다.
        verify(coupangApiClient, never()).get(anyString(), anyString(), any());
        assertThat(result.matchedOrders()).isEqualTo(1);
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void confirm_성공박스는_로컬status를_DEPARTURE로갱신() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000"))
                .willReturn(List.of(line(account, "9001", "4000", "5001", "INSTRUCT")));
        given(orderItemRepository.findByExternalOrderId("4001"))
                .willReturn(List.of(line(account, "9002", "4001", "5002", "INSTRUCT")));
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseMixed());

        ShipmentConfirmResult result = service.confirm(
                xlsx(new Object[][]{{"4000", "i1"}, {"4001", "i2"}}));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(orderItemRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);                       // 실패 박스는 갱신하지 않는다
        assertThat(saved.getValue().get(0).getExternalBoxId()).isEqualTo("9001");
        assertThat(saved.getValue().get(0).getStatus()).isEqualTo("DEPARTURE");
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).hasSize(1);
    }

    @Test
    void confirm_로컬갱신실패해도_성공집계유지() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000"))
                .willReturn(List.of(line(account, "302", "4000", "5001", "INSTRUCT")));
        given(carrierCodeService.resolveDeliveryCompanyCode("COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("302"));
        given(orderItemRepository.saveAll(any())).willThrow(new RuntimeException("db"));

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        // 쿠팡 전송은 이미 성공 — 로컬 갱신 실패가 결과를 뒤집으면 사용자가 재업로드(중복 전송)한다.
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void confirm_폴백응답의_발송완료박스는_스킵() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000")).willReturn(List.of());
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(account));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(ORDER_BY_ID_PATH);
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(singleOrderDelivering());

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).orderId()).isEqualTo("4000");
        assertThat(result.skipped().get(0).status()).isEqualTo("DELIVERING");
        assertThat(result.unmatched()).isEmpty();
        assertThat(result.matchedOrders()).isZero();
    }

    @Test
    void confirm_폴백_전량취소주문은_스킵아님_미매칭유지() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        given(orderItemRepository.findByExternalOrderId("4000")).willReturn(List.of());
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(account));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(ORDER_BY_ID_PATH);
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(singleOrderAllCancelled());

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{"4000", "123"}}));

        // 라인이 빈 이유가 상태가 아니라 전량취소 → "이미 발송됨"으로 거짓 보고하면 안 된다.
        assertThat(result.skipped()).isEmpty();
        assertThat(result.unmatched()).containsExactly("4000");
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    // --- helpers ---

    // ---------- 단건 발송처리(confirmManual, PLAN 2609_11) ----------

    @Test
    void confirmManual_신규모드_박스전체라인전송() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        OrderItem anchor = line(account, "302012345678", "4000019469460", "8001");
        OrderItem l2 = line(account, "302012345678", "4000019469460", "8002");
        OrderItem l3 = line(account, "302012345678", "4000019469460", "8003");
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(anchor));
        given(orderItemRepository.findByExternalOrderId("4000019469460")).willReturn(List.of(anchor, l2, l3));
        given(carrierCodeService.resolveDeliveryCompanyCode(7L, "COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("302012345678"));

        ManualShipmentResult result = service.confirmManual(
                new ManualShipmentRequest(1L, 7L, " 123456789 "));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(pathCaptor.capture(), bodyCaptor.capture(), eq(account));
        assertThat(pathCaptor.getValue()).isEqualTo(INVOICES_PATH.replace("{vendorId}", "A001"));

        JsonNode dtos = objectMapper.readTree(bodyCaptor.getValue()).get("orderSheetInvoiceApplyDtos");
        assertThat(dtos).hasSize(3);
        for (JsonNode dto : dtos) {
            assertThat(dto.get("invoiceNumber").asText()).isEqualTo("123456789");   // trim (D15)
            assertThat(dto.get("deliveryCompanyCode").asText()).isEqualTo("CJGLS");
            assertThat(dto.get("shipmentBoxId").asLong()).isEqualTo(302012345678L);
        }
        assertThat(result.mode()).isEqualTo("CREATE");
        assertThat(result.sentLines()).isEqualTo(3);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void confirmManual_신규모드_성공시_DEPARTURE_writeback() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        OrderItem anchor = line(account, "302012345678", "4000019469460", "8001");
        OrderItem l2 = line(account, "302012345678", "4000019469460", "8002");
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(anchor));
        given(orderItemRepository.findByExternalOrderId("4000019469460")).willReturn(List.of(anchor, l2));
        given(carrierCodeService.resolveDeliveryCompanyCode(7L, "COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("302012345678"));

        ManualShipmentResult result = service.confirmManual(new ManualShipmentRequest(1L, 7L, "123456789"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderItemRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .allMatch(l -> "DEPARTURE".equals(l.getStatus()));
        assertThat(result.resultStatus()).isEqualTo("DEPARTURE");
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void confirmManual_수정모드_updateInvoices경로_writeback없음() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        // 앵커가 이미 배송지시 → 송장수정 모드(D3)
        OrderItem anchor = line(account, "302012345678", "4000019469460", "8001", "DEPARTURE");
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(anchor));
        given(orderItemRepository.findByExternalOrderId("4000019469460")).willReturn(List.of(anchor));
        given(carrierCodeService.resolveDeliveryCompanyCode(7L, "COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getUpdateInvoicesPath()).willReturn(UPDATE_INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("302012345678"));

        ManualShipmentResult result = service.confirmManual(new ManualShipmentRequest(1L, 7L, "987654321"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(pathCaptor.capture(), anyString(), eq(account));
        assertThat(pathCaptor.getValue()).isEqualTo(UPDATE_INVOICES_PATH.replace("{vendorId}", "A001"));
        verify(orderItemRepository, never()).saveAll(any());
        assertThat(result.mode()).isEqualTo("UPDATE");
        assertThat(result.resultStatus()).isNull();
    }

    @Test
    void confirmManual_다른박스라인은_제외() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        OrderItem anchor = line(account, "302012345678", "4000019469460", "8001");
        OrderItem sameBox = line(account, "302012345678", "4000019469460", "8002");
        OrderItem otherBox = line(account, "302012345679", "4000019469460", "8003");
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(anchor));
        given(orderItemRepository.findByExternalOrderId("4000019469460"))
                .willReturn(List.of(anchor, sameBox, otherBox));
        given(carrierCodeService.resolveDeliveryCompanyCode(7L, "COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responseAllSuccess("302012345678"));

        ManualShipmentResult result = service.confirmManual(new ManualShipmentRequest(1L, 7L, "123456789"));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), bodyCaptor.capture(), eq(account));
        JsonNode dtos = objectMapper.readTree(bodyCaptor.getValue()).get("orderSheetInvoiceApplyDtos");
        assertThat(dtos).hasSize(2);
        for (JsonNode dto : dtos) {
            assertThat(dto.get("shipmentBoxId").asLong()).isEqualTo(302012345678L);
        }
        assertThat(result.shipmentBoxId()).isEqualTo("302012345678");
    }

    @Test
    void confirmManual_비쿠팡이면_IllegalArgumentException() {
        MarketplaceAccount account = account(1L, "NAVER", "N001");
        OrderItem anchor = line(account, "302012345678", "4000019469460", "8001");
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(anchor));

        assertThatThrownBy(() -> service.confirmManual(new ManualShipmentRequest(1L, 7L, "123456789")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("쿠팡");
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void confirmManual_박스ID없으면_IllegalArgumentException() {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        OrderItem anchor = line(account, null, "4000019469460", "8001");
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(anchor));

        assertThatThrownBy(() -> service.confirmManual(new ManualShipmentRequest(1L, 7L, "123456789")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("박스");
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void confirmManual_택배사코드_미등록이면_IllegalArgumentException() {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        OrderItem anchor = line(account, "302012345678", "4000019469460", "8001");
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(anchor));
        given(orderItemRepository.findByExternalOrderId("4000019469460")).willReturn(List.of(anchor));
        given(carrierCodeService.resolveDeliveryCompanyCode(7L, "COUPANG"))
                .willThrow(new IllegalArgumentException("선택한 택배사의 COUPANG 코드가 등록되지 않았습니다"));

        assertThatThrownBy(() -> service.confirmManual(new ManualShipmentRequest(1L, 7L, "123456789")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void confirmManual_쿠팡실패응답이면_failed채우고_writeback없음() throws Exception {
        MarketplaceAccount account = account(1L, "COUPANG", "A001");
        OrderItem anchor = line(account, "302", "4000019469460", "8001");
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(anchor));
        given(orderItemRepository.findByExternalOrderId("4000019469460")).willReturn(List.of(anchor));
        given(carrierCodeService.resolveDeliveryCompanyCode(7L, "COUPANG")).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(responsePartialFail());

        ManualShipmentResult result = service.confirmManual(new ManualShipmentRequest(1L, 7L, "123456789"));

        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).shipmentBoxId()).isEqualTo("302");
        assertThat(result.failed().get(0).resultCode()).isEqualTo("DUPLICATE_INVOICE_NUMBER");
        assertThat(result.failed().get(0).message()).isEqualTo("중복 송장번호");
        assertThat(result.resultStatus()).isNull();
        verify(orderItemRepository, never()).saveAll(any());
    }

    @Test
    void confirmManual_라인없으면_IllegalArgumentException() {
        given(orderItemRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmManual(new ManualShipmentRequest(1L, 7L, "123456789")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("주문 라인");
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    private int dtoCount(String body) {
        try {
            return objectMapper.readTree(body).get("orderSheetInvoiceApplyDtos").size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MarketplaceAccount account(Long id, String platform, String vendorId) {
        Seller seller = Seller.builder().id(id).sellerName("셀러" + id).businessRegistration("123-45-6789" + id).build();
        return MarketplaceAccount.builder()
                .id(id).seller(seller).platform(platform).vendorId(vendorId)
                .accessKey("ak").secretKey("sk").isActive(true).build();
    }

    private OrderItem line(MarketplaceAccount account, String boxId, String orderId, String itemId) {
        return line(account, boxId, orderId, itemId, "INSTRUCT");
    }

    private OrderItem line(MarketplaceAccount account, String boxId, String orderId, String itemId, String status) {
        return OrderItem.builder()
                .marketplaceAccount(account).platform(account.getPlatform())
                .externalOrderId(orderId).externalBoxId(boxId).externalItemId(itemId)
                .orderCount(1).cancelCount(0).holdCount(0).status(status).build();
    }

    /** 택배사 고정 양식 xlsx 생성: 헤더(10칸) + 데이터행(주문번호 col5, 운송장번호 col6). */
    private MockMultipartFile xlsx(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("발송처리");
            String[] headers = {"NO", "배송일", "계정", "송하인명", "수하인명", "주문번호", "운송장번호", "내품수량", "운임구분", "택배사"};
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                header.createCell(c).setCellValue(headers[c]);
            }
            int r = 1;
            for (Object[] row : rows) {
                Row dataRow = sheet.createRow(r++);
                setCell(dataRow, 5, row[0]);   // 주문번호
                setCell(dataRow, 6, row[1]);   // 운송장번호
            }
            wb.write(out);
            return new MockMultipartFile("file", "carrier.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private void setCell(Row row, int col, Object value) {
        if (value instanceof Long l) {
            row.createCell(col).setCellValue(l);        // 숫자셀
        } else {
            row.createCell(col).setCellValue((String) value);
        }
    }

    private String responseAllSuccess(String... boxIds) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < boxIds.length; i++) {
            if (i > 0) list.append(",");
            list.append("{\"shipmentBoxId\":\"").append(boxIds[i])
                    .append("\",\"succeed\":true,\"resultCode\":\"OK\",\"resultMessage\":\"\"}");
        }
        return "{\"code\":200,\"data\":{\"responseCode\":0,\"responseList\":[" + list + "]}}";
    }

    /** 쿠팡 단건 발주서 응답: 박스 1개 × vendorItemId 2개(합포장). */
    private String singleOrderTwoLines() {
        return """
            {"code":200,"data":[
              {"orderId":"4000019469460","shipmentBoxId":"302012345678","status":"INSTRUCT",
               "orderItems":[
                 {"vendorItemId":"3823839899","shippingCount":1},
                 {"vendorItemId":"3823839900","shippingCount":2,"cancelCount":1}
               ]}
            ]}
            """;
    }

    /** 쿠팡 단건 발주서 응답: 전량 확정취소 라인 1개 + 정상 라인 1개. */
    private String singleOrderOneCancelledLine() {
        return """
            {"code":200,"data":[
              {"orderId":"4000","shipmentBoxId":"302","status":"INSTRUCT",
               "orderItems":[
                 {"vendorItemId":"5001","shippingCount":1,"cancelCount":1},
                 {"vendorItemId":"5002","shippingCount":1}
               ]}
            ]}
            """;
    }

    /** 박스 2개 중 첫 박스만 성공. */
    private String responseMixed() {
        return "{\"code\":200,\"data\":{\"responseCode\":1,\"responseList\":["
                + "{\"shipmentBoxId\":\"9001\",\"succeed\":true,\"resultCode\":\"OK\",\"resultMessage\":\"\"},"
                + "{\"shipmentBoxId\":\"9002\",\"succeed\":false,"
                + "\"resultCode\":\"DUPLICATE_INVOICE_NUMBER\",\"resultMessage\":\"중복 송장번호\"}]}}";
    }

    /** 쿠팡 단건 발주서 응답: 이미 배송중인 박스. */
    private String singleOrderDelivering() {
        return """
            {"code":200,"data":[
              {"orderId":"4000","shipmentBoxId":"302","status":"DELIVERING",
               "orderItems":[
                 {"vendorItemId":"5001","shippingCount":1}
               ]}
            ]}
            """;
    }

    /** 쿠팡 단건 발주서 응답: 상태는 정상(INSTRUCT)인데 라인이 전량 확정취소. */
    private String singleOrderAllCancelled() {
        return """
            {"code":200,"data":[
              {"orderId":"4000","shipmentBoxId":"302","status":"INSTRUCT",
               "orderItems":[
                 {"vendorItemId":"5001","shippingCount":1,"cancelCount":1}
               ]}
            ]}
            """;
    }

    private String responsePartialFail() {
        return "{\"code\":200,\"data\":{\"responseCode\":1,\"responseList\":["
                + "{\"shipmentBoxId\":\"302\",\"succeed\":false,"
                + "\"resultCode\":\"DUPLICATE_INVOICE_NUMBER\",\"resultMessage\":\"중복 송장번호\"}]}}";
    }
}
