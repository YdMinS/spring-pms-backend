package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.domain.Seller;
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

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Mock
    private CoupangApiClient coupangApiClient;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private CarrierCodeService carrierCodeService;
    @Mock
    private CoupangProperties coupangProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ShipmentConfirmServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShipmentConfirmServiceImpl(
                coupangApiClient, coupangProperties, orderItemRepository, carrierCodeService, objectMapper);
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

    // --- helpers ---

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
        return OrderItem.builder()
                .marketplaceAccount(account).platform(account.getPlatform())
                .externalOrderId(orderId).externalBoxId(boxId).externalItemId(itemId)
                .orderCount(1).cancelCount(0).holdCount(0).status("INSTRUCT").build();
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

    private String responsePartialFail() {
        return "{\"code\":200,\"data\":{\"responseCode\":1,\"responseList\":["
                + "{\"shipmentBoxId\":\"302\",\"succeed\":false,"
                + "\"resultCode\":\"DUPLICATE_INVOICE_NUMBER\",\"resultMessage\":\"중복 송장번호\"}]}}";
    }
}
