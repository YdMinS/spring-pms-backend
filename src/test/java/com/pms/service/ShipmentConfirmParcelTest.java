package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.coupang.OrderUpserter;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 발송처리 → 실물 박스({@code shipment_parcel}) 저장 테스트 (FEATURE_2609_40 / PLAN D8 · D30).
 *
 * <p>{@link ShipmentParcelRecorder} 는 <b>진짜 인스턴스</b>를 넣고 리포지토리만 목으로 둔다 —
 * {@code parcel_seq} 채번과 (배송묶음 × 송장번호) 멱등성이 이 테스트의 검증 대상이기 때문이다.
 * 리포지토리 목은 저장된 행을 리스트에 쌓아 find/count 의미를 흉내낸다(실 DB 의 UNIQUE 를 대신한다).
 */
@ExtendWith(MockitoExtension.class)
class ShipmentConfirmParcelTest {

    private static final String INVOICES_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/invoices";
    private static final String ORDER_BY_ID_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/{orderId}/ordersheets";
    private static final String ORDER_ID = "4000019469460";
    private static final String BOX_A = "302012345678";
    private static final String BOX_B = "302087654321";

    @Mock
    private com.pms.service.coupang.CoupangApiClient coupangApiClient;
    @Mock
    private OrderLineRepository orderLineRepository;
    @Mock
    private CoupangOrderLineRepository coupangOrderLineRepository;
    @Mock
    private CarrierCodeService carrierCodeService;
    @Mock
    private CoupangProperties coupangProperties;
    @Mock
    private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock
    private OrderUpserter orderUpserter;
    @Mock
    private ShipmentParcelRepository shipmentParcelRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ShipmentConfirmServiceImpl service;

    /** 저장된 실물 박스(실 DB 대신). */
    private final List<ShipmentParcel> parcels = new ArrayList<>();
    /** 라인 id → 쿠팡 거울. */
    private final Map<Long, CoupangOrderLine> mirrors = new LinkedHashMap<>();

    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        service = new ShipmentConfirmServiceImpl(
                coupangApiClient, coupangProperties, orderLineRepository, coupangOrderLineRepository,
                marketplaceAccountRepository, carrierCodeService, objectMapper, orderUpserter,
                new ShipmentParcelRecorder(shipmentParcelRepository));

        account = account();

        lenient().when(coupangOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .thenAnswer(invocation -> {
                    List<?> ids = invocation.getArgument(0);
                    return ids.stream().map(mirrors::get).filter(Objects::nonNull).toList();
                });
        lenient().when(shipmentParcelRepository.save(any(ShipmentParcel.class)))
                .thenAnswer(invocation -> {
                    ShipmentParcel parcel = invocation.getArgument(0);
                    parcels.add(parcel);
                    return parcel;
                });
        lenient().when(shipmentParcelRepository.countByOrderShipment_Id(any()))
                .thenAnswer(invocation -> parcels.stream()
                        .filter(p -> Objects.equals(p.getOrderShipment().getId(), invocation.getArgument(0)))
                        .count());
        lenient().when(shipmentParcelRepository.findByOrderShipment_IdAndInvoiceNumber(any(), anyString()))
                .thenAnswer(invocation -> parcels.stream()
                        .filter(p -> Objects.equals(p.getOrderShipment().getId(), invocation.getArgument(0))
                                && p.getInvoiceNumber().equals(invocation.getArgument(1)))
                        .findFirst());
        lenient().when(carrierCodeService.resolveDeliveryCompanyCode(Platform.COUPANG)).thenReturn("CJGLS");
        lenient().when(coupangProperties.getInvoicesPath()).thenReturn(INVOICES_PATH);
    }

    /** 1. 배송 묶음 1개 + 송장 2장 → 박스 2행(parcel_seq 1·2). 택배수량 2로 올린 주문이 이 모양이다. */
    @Test
    void testConfirmSavesAllInvoicesOfSingleShipmentOrder() throws Exception {
        OrderShipment boxA = shipment(11L, BOX_A);
        OrderLine line = line(boxA, "3823839899", OrderStatus.PREPARING);
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(line));
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(successResponse(BOX_A));

        service.confirm(xlsx(new Object[][]{{ORDER_ID, "111111111111"}, {ORDER_ID, "222222222222"}}));

        assertThat(parcels).hasSize(2);
        assertThat(parcels).extracting(ShipmentParcel::getInvoiceNumber)
                .containsExactly("111111111111", "222222222222");
        assertThat(parcels).extracting(ShipmentParcel::getParcelSeq).containsExactly(1, 2);
        assertThat(parcels).allSatisfy(p -> {
            assertThat(p.getStatus()).isEqualTo(ParcelStatus.PENDING);
            assertThat(p.getCarrierCode()).isEqualTo("CJGLS");
            assertThat(p.getOrderShipment()).isSameAs(boxA);
        });
    }

    /** 2. 🔴 D8 회귀 — 송장이 2장이어도 쿠팡 전송은 1회, 대표(첫) 송장만 올라간다. */
    @Test
    void testConfirmSendsOnlyFirstInvoiceToMarket() throws Exception {
        OrderShipment boxA = shipment(11L, BOX_A);
        OrderLine line = line(boxA, "3823839899", OrderStatus.PREPARING);
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(line));
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(successResponse(BOX_A));

        service.confirm(xlsx(new Object[][]{{ORDER_ID, "111111111111"}, {ORDER_ID, "222222222222"}}));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(1)).post(anyString(), body.capture(), any());
        JsonNode dtos = objectMapper.readTree(body.getValue()).get("orderSheetInvoiceApplyDtos");
        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).get("invoiceNumber").asText()).isEqualTo("111111111111");
    }

    /** 3. 🔴 D30 회귀 — 배송 묶음이 2개인 주문은 파일 경로에서 박스를 만들지 않는다(전송은 그대로). */
    @Test
    void testConfirmSkipsParcelWhenOrderHasMultipleShipments() throws Exception {
        OrderShipment boxA = shipment(11L, BOX_A);
        OrderShipment boxB = shipment(12L, BOX_B);
        OrderLine l1 = line(boxA, "3823839899", OrderStatus.PREPARING);
        OrderLine l2 = line(boxB, "3823839900", OrderStatus.PREPARING);
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(l1, l2));
        given(coupangApiClient.post(anyString(), anyString(), any()))
                .willReturn(successResponse(BOX_A, BOX_B));

        service.confirm(xlsx(new Object[][]{{ORDER_ID, "111111111111"}, {ORDER_ID, "222222222222"}}));

        assertThat(parcels).isEmpty();
        verify(shipmentParcelRepository, never()).save(any());
        verify(coupangApiClient, times(1)).post(anyString(), anyString(), any());
    }

    /**
     * 3-b. 🔴 묶음 수를 세는 소스가 {@code sendable} 이 아니라 <b>그 주문의 전체 라인</b>이라는 회귀.
     * 박스 2개 중 하나가 이미 발송(SHIPPED)이라 전송 대상이 1개뿐이어도 박스를 만들면 안 된다.
     */
    @Test
    void testConfirmCountsShipmentsIncludingAlreadySentBox() throws Exception {
        OrderShipment boxA = shipment(11L, BOX_A);
        OrderShipment boxB = shipment(12L, BOX_B);
        OrderLine alreadySent = line(boxA, "3823839899", OrderStatus.SHIPPED);
        OrderLine pending = line(boxB, "3823839900", OrderStatus.PREPARING);
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(alreadySent, pending));
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(successResponse(BOX_B));

        service.confirm(xlsx(new Object[][]{{ORDER_ID, "111111111111"}}));

        assertThat(parcels).isEmpty();
        verify(shipmentParcelRepository, never()).save(any());
        verify(coupangApiClient, times(1)).post(anyString(), anyString(), any());
    }

    /** 4. 같은 파일을 두 번 올려도 박스가 늘지 않는다(D7 멱등). */
    @Test
    void testConfirmSkipsDuplicateInvoice() throws Exception {
        OrderShipment boxA = shipment(11L, BOX_A);
        OrderLine line = line(boxA, "3823839899", OrderStatus.PREPARING);
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(line));
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(successResponse(BOX_A));

        service.confirm(xlsx(new Object[][]{{ORDER_ID, "111111111111"}, {ORDER_ID, "222222222222"}}));
        service.confirm(xlsx(new Object[][]{{ORDER_ID, "111111111111"}, {ORDER_ID, "222222222222"}}));

        assertThat(parcels).hasSize(2);
    }

    /** 5. 단건 수동 발송처리 → 그 박스에 행 1개(묶음이 지정돼 있으므로 묶음 수와 무관). */
    @Test
    void testConfirmManualSavesInvoice() {
        OrderShipment boxA = shipment(11L, BOX_A);
        OrderShipment boxB = shipment(12L, BOX_B);
        OrderLine anchor = line(boxA, "3823839899", OrderStatus.PREPARING);
        OrderLine other = line(boxB, "3823839900", OrderStatus.PREPARING);
        given(orderLineRepository.findWithAccountAndSellerById(anchor.getId())).willReturn(Optional.of(anchor));
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(anchor, other));
        given(carrierCodeService.validateDeliveryCompanyCode("HANJIN", Platform.COUPANG)).willReturn("HANJIN");
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(successResponse(BOX_A));

        service.confirmManual(new ManualShipmentRequest(anchor.getId(), "HANJIN", "333333333333"));

        assertThat(parcels).hasSize(1);
        assertThat(parcels.get(0).getInvoiceNumber()).isEqualTo("333333333333");
        assertThat(parcels.get(0).getCarrierCode()).isEqualTo("HANJIN");
        assertThat(parcels.get(0).getParcelSeq()).isEqualTo(1);
        assertThat(parcels.get(0).getOrderShipment()).isSameAs(boxA);
    }

    /** 5-b. 전량 발송 완료(전송 0회, skipped)여도 송장은 이미 발급됐으므로 박스는 저장된다. */
    @Test
    void testConfirmSavesParcelForFullyShippedOrder() throws Exception {
        OrderShipment boxA = shipment(11L, BOX_A);
        OrderLine shipped = line(boxA, "3823839899", OrderStatus.SHIPPED);
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(shipped));

        ShipmentConfirmResult result = service.confirm(xlsx(new Object[][]{{ORDER_ID, "111111111111"}}));

        assertThat(result.skipped()).hasSize(1);
        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
        assertThat(parcels).hasSize(1);
        assertThat(parcels.get(0).getInvoiceNumber()).isEqualTo("111111111111");
    }

    /** 5-c. 폴백으로 확정된 주문(DB 에 없어 단건 조회로 적재)도 박스가 저장된다. */
    @Test
    void testConfirmSavesParcelForFallbackConfirmedOrder() throws Exception {
        OrderShipment boxA = shipment(11L, BOX_A);
        OrderLine stored = line(boxA, "3823839899", OrderStatus.PREPARING);
        // 1번째 호출(본 루프) = 없음 → 폴백. 2번째 호출(폴백 적재 후) = 적재된 라인.
        given(orderLineRepository.findByExternalOrderId(ORDER_ID))
                .willReturn(List.of())
                .willReturn(List.of(stored));
        given(marketplaceAccountRepository.findByIsActiveTrue()).willReturn(List.of(account));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(ORDER_BY_ID_PATH);
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(fallbackOrderResponse());
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(successResponse(BOX_A));

        service.confirm(xlsx(new Object[][]{{ORDER_ID, "111111111111"}}));

        assertThat(parcels).hasSize(1);
        assertThat(parcels.get(0).getInvoiceNumber()).isEqualTo("111111111111");
        assertThat(parcels.get(0).getOrderShipment()).isSameAs(boxA);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private MarketplaceAccount account() {
        Seller seller = Seller.builder().id(1L).sellerName("셀러1").businessRegistration("123-45-67890").build();
        return MarketplaceAccountFixture.coupangStubBuilder("A001", null)
                .id(1L).seller(seller).platform(Platform.COUPANG).isActive(true).build();
    }

    private OrderShipment shipment(Long id, String externalShipmentId) {
        Order order = Order.builder().id(10L).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(ORDER_ID).build();
        return OrderShipment.builder().id(id).order(order).externalShipmentId(externalShipmentId).build();
    }

    /** core 라인 1건 + 그 쿠팡 거울. 라인 id 는 vendorItemId 를 그대로 쓴다. */
    private OrderLine line(OrderShipment shipment, String vendorItemId, OrderStatus status) {
        OrderLine line = OrderLine.builder()
                .id(Long.parseLong(vendorItemId)).order(shipment.getOrder()).orderShipment(shipment)
                .orderQty(1).cancelQty(0).holdQty(0).status(status).build();
        mirrors.put(line.getId(), CoupangOrderLine.builder()
                .id(line.getId()).orderLine(line).marketplaceAccount(account)
                .shipmentBoxId(shipment.getExternalShipmentId()).orderIdRaw(ORDER_ID).vendorItemId(vendorItemId)
                .build());
        return line;
    }

    /** 택배사 고정 양식 xlsx: 헤더 + (주문번호 col5, 운송장번호 col6). */
    private MockMultipartFile xlsx(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("발송처리");
            Row header = sheet.createRow(0);
            header.createCell(5).setCellValue("주문번호");
            header.createCell(6).setCellValue("운송장번호");
            int r = 1;
            for (Object[] row : rows) {
                Row dataRow = sheet.createRow(r++);
                dataRow.createCell(5).setCellValue((String) row[0]);
                dataRow.createCell(6).setCellValue((String) row[1]);
            }
            wb.write(out);
            return new MockMultipartFile("file", "carrier.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private String successResponse(String... boxIds) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < boxIds.length; i++) {
            if (i > 0) {
                list.append(",");
            }
            list.append("{\"shipmentBoxId\":\"").append(boxIds[i])
                    .append("\",\"succeed\":true,\"resultCode\":\"OK\",\"resultMessage\":\"\"}");
        }
        return "{\"code\":200,\"data\":{\"responseCode\":0,\"responseList\":[" + list + "]}}";
    }

    /** 폴백 단건 조회 응답(박스 1개 · 라인 1개). */
    private String fallbackOrderResponse() {
        return """
            {"code":200,"data":[
              {"orderId":"%s","shipmentBoxId":"%s","status":"INSTRUCT",
               "orderItems":[{"vendorItemId":"3823839899","shippingCount":1}]}
            ]}
            """.formatted(ORDER_ID, BOX_A);
    }
}
