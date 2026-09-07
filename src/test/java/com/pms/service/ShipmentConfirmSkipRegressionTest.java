package com.pms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangOrderStatus;
import com.pms.service.coupang.OrderUpserter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 발송처리 스킵 판정 회귀 (FEATURE_2609_26 / 04).
 *
 * <p>스킵 집합이 {@code Set<String>}(쿠팡 원문) → {@code Set<OrderStatus>}(중립)로 바뀌었다.
 * 이 테스트가 고정하는 것은 <b>어느 라인이 걸러지는가가 이전과 같다</b>는 것이다(2609_07 D1):
 * 쿠팡 6코드 중 배송지시 이상 4코드(DEPARTURE·DELIVERING·FINAL_DELIVERY·NONE_TRACKING)는 전송하지 않고,
 * 활성 2코드(ACCEPT·INSTRUCT)는 전송한다.
 *
 * <p>🔴 {@code CANCELLED} 를 스킵 집합에 넣지 않는 것도 여기서 함께 고정한다 — 전량취소 라인은 지금도
 * 라인 수 0 으로 걸러지므로 넣으면 판정이 두 겹이 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShipmentConfirmSkipRegressionTest {

    private static final String INVOICES_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/invoices";
    private static final String ORDER_ID = "4000019469460";
    private static final String BOX_ID = "302012345678";
    private static final String VENDOR_ITEM_ID = "3823839899";

    @Mock private CoupangApiClient coupangApiClient;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private CoupangOrderLineRepository coupangOrderLineRepository;
    @Mock private CarrierCodeService carrierCodeService;
    @Mock private CoupangProperties coupangProperties;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private OrderUpserter orderUpserter;

    private ShipmentConfirmServiceImpl service;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        service = new ShipmentConfirmServiceImpl(
                coupangApiClient, coupangProperties, orderLineRepository, coupangOrderLineRepository,
                marketplaceAccountRepository, carrierCodeService, new ObjectMapper(), orderUpserter);

        Seller seller = Seller.builder().id(1L).sellerName("셀러A").businessRegistration("123-45-67890").build();
        account = MarketplaceAccountFixture.coupangStubBuilder("A001", null)
                .id(1L).seller(seller).platform(Platform.COUPANG).isActive(true).build();

        given(carrierCodeService.resolveDeliveryCompanyCode(Platform.COUPANG)).willReturn("CJGLS");
        given(coupangProperties.getInvoicesPath()).willReturn(INVOICES_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any()))
                .willReturn("{\"code\":200,\"data\":{\"responseList\":[{\"shipmentBoxId\":\"" + BOX_ID
                        + "\",\"succeed\":true,\"resultCode\":\"OK\",\"resultMessage\":\"\"}]}}");
    }

    @ParameterizedTest
    @EnumSource(CoupangOrderStatus.class)
    void 전송여부가_쿠팡6코드_기준으로_이전과같다(CoupangOrderStatus coupangStatus) throws Exception {
        OrderStatus status = coupangStatus.toOrderStatus();
        OrderLine line = line(status);
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(line));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(mirror(line)));

        ShipmentConfirmResult result = service.confirm(xlsx());

        // 배송지시 이상(= 중립 terminal)만 스킵. 그 판정은 OrderStatus 가 소유한다.
        boolean expectedSkip = status.isTerminal();
        if (expectedSkip) {
            assertThat(result.skipped()).hasSize(1);
            assertThat(result.skipped().get(0).status()).isEqualTo(status.name());
            assertThat(result.matchedOrders()).isZero();
        } else {
            assertThat(result.skipped()).isEmpty();
            assertThat(result.matchedOrders()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PREPARING"})
    void 전량취소_라인은_스킵집합이_아니라_전송대상이다(OrderStatus status) throws Exception {
        // 🔴 CANCELLED 를 스킵 집합에 넣지 않는다 — 전량취소 라인은 여기서 걸러지지 않고 그대로 전송된다.
        OrderLine line = OrderLine.builder()
                .id(Long.parseLong(VENDOR_ITEM_ID)).order(order()).orderShipment(shipment())
                .orderQty(2).cancelQty(2).holdQty(0).status(status).build();
        given(orderLineRepository.findByExternalOrderId(ORDER_ID)).willReturn(List.of(line));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(mirror(line)));

        ShipmentConfirmResult result = service.confirm(xlsx());

        assertThat(result.skipped()).isEmpty();
        assertThat(result.matchedOrders()).isEqualTo(1);
    }

    // --- fixtures ---

    private Order order() {
        return Order.builder().id(10L).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(ORDER_ID).build();
    }

    private OrderShipment shipment() {
        return OrderShipment.builder().id(20L).order(order()).externalShipmentId(BOX_ID).build();
    }

    private OrderLine line(OrderStatus status) {
        return OrderLine.builder()
                .id(Long.parseLong(VENDOR_ITEM_ID)).order(order()).orderShipment(shipment())
                .orderQty(1).cancelQty(0).holdQty(0).status(status).build();
    }

    private CoupangOrderLine mirror(OrderLine line) {
        return CoupangOrderLine.builder()
                .id(line.getId()).orderLine(line).marketplaceAccount(account)
                .shipmentBoxId(BOX_ID).orderIdRaw(ORDER_ID).vendorItemId(VENDOR_ITEM_ID).build();
    }

    /** 택배사 고정 양식 xlsx 1행 — 주문번호 col5, 운송장번호 col6. */
    private MockMultipartFile xlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("발송처리");
            sheet.createRow(0).createCell(5).setCellValue("주문번호");
            Row row = sheet.createRow(1);
            row.createCell(5).setCellValue(ORDER_ID);
            row.createCell(6).setCellValue("123456789012");
            wb.write(out);
            return new MockMultipartFile("file", "result.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}
