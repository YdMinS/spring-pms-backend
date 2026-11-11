package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.dto.request.ManualShipmentRequest.RecordChoice;
import com.pms.dto.response.LotteonInvoiceRecordsResponse;
import com.pms.dto.response.LotteonInvoiceRecordsResponse.Record;
import com.pms.exception.BusinessException;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.ManualShipmentResult.RecordOutcome;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentInvoiceAdapter.FileOutcome;
import com.pms.service.ShipmentInvoiceAdapter.ResultFile;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderApi.LineFailure;
import com.pms.service.lotteon.LotteonOrderSellerServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FEATURE_2611_23 / 05 — LotteON invoice registration [298] (D18), invoice update [139] (D19 · R13 ~ R16 · R20),
 * reserved invoices (D49 ③ · R10), the internal-stage switch (D49 ①) and the delivery-record list (R17 ~ R19).
 */
@ExtendWith(MockitoExtension.class)
class LotteonInvoiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private LotteonOrderLineRepository lotteonOrderLineRepository;
    @Mock private CarrierCodeService carrierCodeService;
    @Mock private ShipmentParcelRecorder shipmentParcelRecorder;
    @Mock private LotteonOrderApi lotteonOrderApi;
    @Mock private InvoiceWriteBack invoiceWriteBack;

    private final MarketplaceAccount account = MarketplaceAccount.builder().id(9L).platform(Platform.LOTTEON)
            .seller(Seller.builder().id(3L).sellerName("판매자").build())
            .build();
    private final OrderShipment shipment = shipment(account, "OD1", 10L, "OD1-1");

    private LotteonShipmentInvoiceAdapter adapter() {
        return new LotteonShipmentInvoiceAdapter(orderLineRepository, lotteonOrderLineRepository, carrierCodeService,
                shipmentParcelRecorder, lotteonOrderApi, invoiceWriteBack);
    }

    private LotteonOrderSellerServiceImpl sellerService() {
        return new LotteonOrderSellerServiceImpl(orderLineRepository, lotteonOrderLineRepository, lotteonOrderApi,
                carrierCodeService);
    }

    @Test
    void shipFromFile_skipsPaidOrderAndShipsPreparingLinesExceptRefusedOnes() {
        OrderLine paid = line(11L, shipment(account, "OD1", 11L, "OD1-1"), OrderStatus.PAID, 1, 0, "상품1");
        OrderLine second = line(21L, shipment(account, "OD2", 21L, "OD2-1"), OrderStatus.PREPARING, 1, 0, "상품2");
        OrderLine third = line(31L, shipment(account, "OD3", 31L, "OD3-1"), OrderStatus.PREPARING, 1, 0, "상품3");
        Map<String, List<OrderLine>> orders = new LinkedHashMap<>();
        orders.put("OD1", List.of(paid));
        orders.put("OD2", List.of(second));
        orders.put("OD3", List.of(third));
        Map<String, List<String>> invoices = new LinkedHashMap<>();
        invoices.put("OD2", List.of("222"));
        invoices.put("OD3", List.of("333"));
        given(carrierCodeService.resolveDeliveryCompanyCode(Platform.LOTTEON)).willReturn("0002");
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(second, "1"), mirror(third, "1")));
        given(lotteonOrderApi.informProgress(eq(account), anyList()))
                .willReturn(List.of(new LineFailure("OD3", "1", "3023", "처리가능수량보다 주문수량이 많습니다.")));

        FileOutcome outcome = adapter().shipFromFile(new ResultFile(invoices, List.of(), new HashMap<>()), orders);

        assertThat(outcome.skipped()).hasSize(1);
        assertThat(outcome.skipped().get(0).reason()).isEqualTo("롯데ON에서 발주처리를 먼저 해 주세요.");
        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(outcome.failed()).hasSize(1);
        assertThat(outcome.failed().get(0).resultCode()).isEqualTo("3023");
        assertThat(outcome.failed().get(0).message()).isEqualTo("처리가능수량보다 주문수량이 많습니다.");
        Map<String, Object> first = sentLines().get(0);
        assertThat(first.get("odPrgsStepCd")).isEqualTo("13");
        assertThat(first.get("dvCoCd")).isEqualTo("0002");
        assertThat(first.get("invcNoList")).isEqualTo(List.of("222"));
        assertThat(first.get("invcNbr")).isEqualTo("1");
        ArgumentCaptor<List<String>> keys = listCaptor();
        verify(invoiceWriteBack).markDeparted(keys.capture(), anyMap());
        assertThat(keys.getValue()).containsExactly("OD2|1");
    }

    @Test
    void shipManual_paidLineIsRefused() {
        OrderLine anchor = line(1L, shipment, OrderStatus.PAID, 3, 1, "물티슈");

        assertThatThrownBy(() -> adapter().shipManual(new ManualShipmentRequest(1L, "0001", "C333"), anchor, account,
                "C333"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("롯데ON 주문은 [발주처리] 뒤에 송장을 등록할 수 있습니다.");
        verifyNoInteractions(lotteonOrderApi);
    }

    @Test
    void shipManual_preparingSends298ForEveryLineOfTheBundle() {
        OrderLine line1 = line(1L, shipment, OrderStatus.PREPARING, 3, 1, "물티슈");
        OrderLine line2 = line(2L, shipment, OrderStatus.PREPARING, 1, 0, "칫솔");
        given(carrierCodeService.validateDeliveryCompanyCode("0001", Platform.LOTTEON)).willReturn("0001");
        given(orderLineRepository.findByExternalOrderId("OD1")).willReturn(List.of(line1, line2));
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(line1, "1"), mirror(line2, "2")));
        given(lotteonOrderApi.informProgress(eq(account), anyList())).willReturn(List.of());

        ManualShipmentResult result = adapter().shipManual(new ManualShipmentRequest(1L, "0001", "C333"), line1,
                account, "C333");

        assertThat(result.mode()).isEqualTo("CREATE");
        assertThat(result.sentLines()).isEqualTo(2);
        assertThat(result.resultStatus()).isEqualTo("SHIPPED");
        assertThat(sentLines().get(0).get("slQty")).isEqualTo("2");
        verify(shipmentParcelRecorder, times(1)).record(shipment, "C333", "0001", null);
        assertThat(result.records()).isEmpty();
    }

    @Test
    void shipManual_shippedModifiesEachChosenRecordSeparatelyWithItsCurrentInvoice() {
        OrderLine line1 = line(1L, shipment, OrderStatus.SHIPPED, 3, 1, "물티슈");
        given(carrierCodeService.validateDeliveryCompanyCode("0001", Platform.LOTTEON)).willReturn("0001");
        given(orderLineRepository.findByExternalOrderId("OD1")).willReturn(List.of(line1));
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(mirror(line1, "1")));
        given(lotteonOrderApi.progressStatesOfOrder(account, "OD1")).willReturn(List.of(
                row("1", "2", "13", "A111", null, null), row("1", "3", "13", "B222", null, null)));
        doNothing()
                .doThrow(new BusinessException("등록 되지 않은 운송장번호 입니다.", HttpStatus.BAD_GATEWAY))
                .when(lotteonOrderApi).modifyInvoice(eq(account), anyList());
        ManualShipmentRequest request = update(new RecordChoice(1L, "2"), new RecordChoice(1L, "3"));

        ManualShipmentResult result = adapter().shipManual(request, line1, account, "C333");

        verify(lotteonOrderApi, times(1)).progressStatesOfOrder(account, "OD1");
        ArgumentCaptor<List<Map<String, Object>>> bodies = listCaptor();
        verify(lotteonOrderApi, times(2)).modifyInvoice(eq(account), bodies.capture());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("odNo", "OD1");
        expected.put("odSeq", "1");
        expected.put("procSeq", "2");
        expected.put("invcNo", "A111");
        expected.put("modDvCoCd", "0001");
        expected.put("modInvcNo", "C333");
        assertThat(bodies.getAllValues().get(0)).containsExactly(expected);
        assertThat(result.mode()).isEqualTo("UPDATE");
        assertThat(result.sentLines()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).containsExactly(new FailedBox("OD1-1", "ERROR", "등록 되지 않은 운송장번호 입니다."));
        assertThat(result.records()).containsExactly(
                new RecordOutcome(1L, "2", "A111", true, null),
                new RecordOutcome(1L, "3", "B222", false, "등록 되지 않은 운송장번호 입니다."));
        assertThat(result.resultStatus()).isNull();
        verify(invoiceWriteBack, never()).markDeparted(any(), any());
        verify(shipmentParcelRecorder, times(1)).record(shipment, "C333", "0001", null);
    }

    @Test
    void shipManual_blankInvoiceRecordSends298OnceAndMissingOrUnpickableRecordFails() {
        OrderLine line1 = line(1L, shipment, OrderStatus.SHIPPED, 3, 1, "물티슈");
        given(carrierCodeService.validateDeliveryCompanyCode("0001", Platform.LOTTEON)).willReturn("0001");
        given(orderLineRepository.findByExternalOrderId("OD1")).willReturn(List.of(line1));
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(mirror(line1, "1")));
        given(lotteonOrderApi.progressStatesOfOrder(account, "OD1")).willReturn(List.of(
                row("1", "1", "13", null, null, null), row("1", "4", "21", "D444", null, null)));
        given(lotteonOrderApi.informProgress(eq(account), anyList())).willReturn(List.of());
        ManualShipmentRequest request = update(new RecordChoice(1L, "1"), new RecordChoice(1L, "9"),
                new RecordChoice(1L, "4"));

        ManualShipmentResult result = adapter().shipManual(request, line1, account, "C333");

        List<Map<String, Object>> sent = sentLines();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("procSeq")).isEqualTo("1");
        assertThat(sent.get(0).get("invcNoList")).isEqualTo(List.of("C333"));
        assertThat(sent.get(0).get("odPrgsStepCd")).isEqualTo("13");
        verify(lotteonOrderApi, never()).modifyInvoice(any(), anyList());
        assertThat(result.records()).containsExactly(
                new RecordOutcome(1L, "1", null, true, null),
                new RecordOutcome(1L, "9", null, false, "롯데ON 에 발송 기록이 없어 고칠 수 없습니다."),
                new RecordOutcome(1L, "4", null, false, "롯데ON 에 발송 기록이 없어 고칠 수 없습니다."));
        assertThat(result.failed()).containsExactly(
                new FailedBox("OD1-1", "ERROR", "롯데ON 에 발송 기록이 없어 고칠 수 없습니다."),
                new FailedBox("OD1-1", "ERROR", "롯데ON 에 발송 기록이 없어 고칠 수 없습니다."));
        verify(invoiceWriteBack, never()).markDeparted(any(), any());
    }

    @Test
    void shipManual_shippedWithoutRecordsSendsNothing() {
        OrderLine line1 = line(1L, shipment, OrderStatus.SHIPPED, 3, 1, "물티슈");
        given(carrierCodeService.validateDeliveryCompanyCode("0001", Platform.LOTTEON)).willReturn("0001");

        ManualShipmentResult result = adapter().shipManual(update(), line1, account, "C333");

        verifyNoInteractions(lotteonOrderApi, shipmentParcelRecorder);
        assertThat(result.sentLines()).isZero();
        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).isEmpty();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void sendReservedInvoices_bundleSucceedsOnlyWhenNoLineIsRefused() {
        OrderLine first = line(1L, shipment, OrderStatus.PREPARING, 1, 0, "물티슈");
        OrderLine second = line(2L, shipment(account, "OD2", 20L, "OD2-1"), OrderStatus.PREPARING, 1, 0, "칫솔");
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(first, "1"), mirror(second, "1")));
        given(lotteonOrderApi.informProgress(eq(account), anyList())).willAnswer(inv -> {
            List<Map<String, Object>> body = inv.getArgument(1);
            return "OD2".equals(body.get(0).get("odNo"))
                    ? List.of(new LineFailure("OD2", "1", "3023", "수량 초과"))
                    : List.of();
        });
        Map<Long, List<OrderLine>> linesByShipment = new LinkedHashMap<>();
        linesByShipment.put(10L, List.of(first));
        linesByShipment.put(20L, List.of(second));

        ReservedInvoiceResult result = adapter().sendReservedInvoices(List.of(
                new ReservedInvoice(10L, "OD1", "0001", List.of("111")),
                new ReservedInvoice(20L, "OD2", "0001", List.of("222"))), linesByShipment);

        assertThat(result.succeededShipmentIds()).containsExactly(10L);
        assertThat(result.failedReasons()).containsExactlyEntriesOf(Map.of(20L, "수량 초과"));
        verify(lotteonOrderApi, times(2)).informProgress(eq(account), anyList());
    }

    @Test
    void acknowledgeAdapter_opensTheInternalStage() {
        assertThat(new LotteonOrderAcknowledgeAdapter(lotteonOrderApi, lotteonOrderLineRepository,
                orderLineRepository).internalStage()).isTrue();
    }

    @Test
    void invoiceRecords_listsEveryLineOfTheBundleWithItsDeliveryRecords() {
        OrderLine line1 = line(1L, shipment, OrderStatus.SHIPPED, 3, 1, "물티슈");
        OrderLine line2 = line(2L, shipment, OrderStatus.SHIPPED, 1, 0, "칫솔");
        OrderLine line3 = line(3L, shipment(account, "OD1", 11L, "OD1-3"), OrderStatus.SHIPPED, 1, 0, "수건");
        given(orderLineRepository.findWithAccountAndSellerById(1L)).willReturn(Optional.of(line1));
        given(orderLineRepository.findByExternalOrderId("OD1")).willReturn(List.of(line2, line1, line3));
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(line1, "1"), mirror(line2, "2")));
        ObjectNode returned = row("1", "3", "24", null, null, null);
        returned.put("dvRtrvDvsCd", "RTRV");
        returned.put("odTypCd", "40");
        given(lotteonOrderApi.progressStatesOfOrder(account, "OD1")).willReturn(List.of(
                row("1", "1", "13", "A111", "0001", "20261101142000"),
                row("1", "2", "21", null, null, null),
                returned,
                row("2", "1", "13", "A111", "9999", null)));
        given(carrierCodeService.findOptions(Platform.LOTTEON))
                .willReturn(List.of(new CarrierOption("0001", "롯데택배", true)));

        List<LotteonInvoiceRecordsResponse> result = sellerService().invoiceRecords(1L);

        assertThat(result).containsExactly(
                new LotteonInvoiceRecordsResponse(1L, "물티슈", List.of(
                        new Record("1", "A111", "0001", "롯데택배", "13", "발송완료",
                                LocalDateTime.of(2026, 11, 1, 14, 20)),
                        new Record("2", null, null, null, "21", "취소완료", null))),
                new LotteonInvoiceRecordsResponse(2L, "칫솔", List.of(
                        new Record("1", "A111", "9999", "9999", "13", "발송완료", null))));
        verify(lotteonOrderApi, times(1)).progressStatesOfOrder(account, "OD1");
    }

    @Test
    void invoiceRecords_rejectsALineThatIsNotLotteon() {
        MarketplaceAccount elevenst = MarketplaceAccount.builder().id(7L).platform(Platform.ELEVENST).build();
        OrderLine line = line(5L, shipment(elevenst, "E1", 50L, "D1"), OrderStatus.SHIPPED, 1, 0, "상품");
        given(orderLineRepository.findWithAccountAndSellerById(5L)).willReturn(Optional.of(line));

        assertThatThrownBy(() -> sellerService().invoiceRecords(5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("롯데ON 주문이 아닙니다");
        verifyNoInteractions(lotteonOrderApi);
    }

    private List<Map<String, Object>> sentLines() {
        ArgumentCaptor<List<Map<String, Object>>> body = listCaptor();
        verify(lotteonOrderApi).informProgress(eq(account), body.capture());
        return body.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static ManualShipmentRequest update(RecordChoice... records) {
        return new ManualShipmentRequest(1L, "0001", "C333", null, null, null, List.of(records));
    }

    private static OrderShipment shipment(MarketplaceAccount owner, String orderId, Long id, String bundleId) {
        Order order = Order.builder().id(id + 100).marketplaceAccount(owner).platform(owner.getPlatform())
                .externalOrderId(orderId).build();
        return OrderShipment.builder().id(id).order(order).externalShipmentId(bundleId).build();
    }

    private static OrderLine line(Long id, OrderShipment bundle, OrderStatus status, int orderQty, int cancelQty,
                                  String name) {
        return OrderLine.builder().id(id).order(bundle.getOrder()).orderShipment(bundle).status(status)
                .itemName(name).orderQty(orderQty).cancelQty(cancelQty).holdQty(0).build();
    }

    private LotteonOrderLine mirror(OrderLine line, String odSeq) {
        return LotteonOrderLine.builder().id(line.getId()).orderLine(line).marketplaceAccount(account)
                .odNo(line.getOrder().getExternalOrderId()).odSeq(odSeq).procSeq("1").platformStatus("13").build();
    }

    /** One [140] row — delivery ({@code DV}) of an order ({@code odTypCd} 10) unless overwritten. */
    private static ObjectNode row(String odSeq, String procSeq, String step, String invoice, String carrier,
                                  String time) {
        ObjectNode row = JSON.createObjectNode();
        row.put("odNo", "OD1");
        row.put("odSeq", odSeq);
        row.put("procSeq", procSeq);
        row.put("dvRtrvDvsCd", "DV");
        row.put("odTypCd", "10");
        row.put("odPrgsStepCd", step);
        if (invoice != null) {
            row.put("invcNo", invoice);
        }
        if (carrier != null) {
            row.put("dvCoCd", carrier);
        }
        if (time != null) {
            row.put("dvTrcStatDttm", time);
        }
        return row;
    }
}
