package com.pms.service;

import com.pms.config.CoupangProperties;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.InternalShipmentStage;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ReservedItemProgress;
import com.pms.domain.ReservedItemResult;
import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentItem;
import com.pms.domain.ReservedShipmentStatus;
import com.pms.domain.Seller;
import com.pms.dto.response.ReservedShipmentRowResponse;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ReservedShipmentItemRepository;
import com.pms.repository.ReservedShipmentRepository;
import com.pms.service.OrderAcknowledgeAdapter.AcknowledgeOutcome;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstOrderActions;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstOrderUpserter;
import com.pms.service.elevenst.ElevenstPaths;
import com.pms.service.reservation.InternalShipmentStageService;
import com.pms.service.reservation.InternalShipmentStageServiceImpl;
import com.pms.service.reservation.InternalStageResult;
import com.pms.service.reservation.ReservedShipmentExecutor;
import com.pms.service.reservation.ReservedShipmentServiceImpl;
import com.pms.service.reservation.ReservedShipmentSettler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * FEATURE_2611_23 — 11st opens the internal-stage bundle (D51 · D53 · R1 · R2 · R3), the reservation reads carrier
 * codes only for the file's marketplaces (D50 · D56 ②) and each reserved row carries its marketplace (D64 ①).
 */
@ExtendWith(MockitoExtension.class)
class ElevenstInternalStageTest {

    @Mock private ElevenstOrderActions elevenstOrderActions;
    @Mock private ElevenstOrderLineRepository elevenstOrderLineRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private OrderShipmentRepository orderShipmentRepository;
    @Mock private ReservedShipmentItemRepository reservedShipmentItemRepository;
    @Mock private ReservedShipmentRepository reservedShipmentRepository;
    @Mock private ReservedShipmentSettler reservedShipmentSettler;
    @Mock private ElevenstOrderApi elevenstOrderApi;
    @Mock private ElevenstOrderUpserter elevenstOrderUpserter;
    @Mock private CoupangProperties coupangProperties;
    @Mock private CarrierCodeService carrierCodeService;
    @Mock private ShipmentParcelRecorder shipmentParcelRecorder;
    @Mock private ElevenstManualShipper elevenstManualShipper;
    @Mock private InvoiceWriteBack invoiceWriteBack;
    @Mock private ShipmentConfirmService shipmentConfirmService;
    @Mock private InternalShipmentStageService internalShipmentStageService;
    @Mock private ReservedShipmentExecutor reservedShipmentExecutor;

    private final MarketplaceAccount account = MarketplaceAccount.builder().id(7L).platform(Platform.ELEVENST)
            .seller(Seller.builder().id(1L).sellerName("판매자").build()).build();

    private ElevenstOrderAcknowledgeAdapter acknowledgeAdapter() {
        return new ElevenstOrderAcknowledgeAdapter(elevenstOrderActions, elevenstOrderLineRepository,
                orderLineRepository);
    }

    @Test
    void acknowledge_reportsDlvNoOnlyWhenEveryProductGroupSucceeded() {
        OrderLine line1 = line(1L, "O1", shipment(10L, "D1", null), OrderStatus.PAID);
        OrderLine line2 = line(2L, "O2", shipment(20L, "D2", null), OrderStatus.PAID);
        OrderLine line3 = line(3L, "O2", line2.getOrderShipment(), OrderStatus.PAID);
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(
                mirror(line1, "P1", "202", null), mirror(line2, "P2", "202", null), mirror(line3, "P3", "202", null)));
        // lenient: the D1 call is meant to reach the same method with other arguments
        lenient().doThrow(new IllegalStateException("거절")).when(elevenstOrderActions)
                .acknowledge(any(), any(), eq("D2"));
        ElevenstOrderAcknowledgeAdapter adapter = acknowledgeAdapter();

        AcknowledgeOutcome outcome = adapter.acknowledge(List.of(line1, line2, line3));

        assertThat(adapter.internalStage()).isTrue();
        assertThat(outcome.succeededBoxIds()).containsExactly("D1");
        assertThat(outcome.failed()).hasSize(2);
    }

    @Test
    void internalStageExcludedLineIds_returnsReservedSaleAndVisitPickupLines() {
        OrderLine line1 = line(1L, "O1", shipment(10L, "D1", null), OrderStatus.PAID);
        OrderLine line2 = line(2L, "O2", shipment(20L, "D2", null), OrderStatus.PAID);
        OrderLine line3 = line(3L, "O3", shipment(30L, "D3", null), OrderStatus.PAID);
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(
                mirror(line1, "P1", "202", "<order><visitDlvYn>N</visitDlvYn></order>"),
                mirror(line2, "P2", "201", null),
                mirror(line3, "P3", "202", "<order><visitDlvYn>Y</visitDlvYn></order>")));

        Set<Long> excluded = acknowledgeAdapter().internalStageExcludedLineIds(List.of(line1, line2, line3));

        assertThat(excluded).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void markInternal_skipsElevenstBundleWhoseOnlyPaidLineIsExcluded() {
        OrderLine line1 = line(1L, "O1", shipment(10L, "D1", null), OrderStatus.PAID);
        OrderLine line2 = line(2L, "O2", shipment(20L, "D2", null), OrderStatus.PAID);
        given(orderLineRepository.findWithAccountByIdIn(anyList())).willReturn(List.of(line1, line2));
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(
                mirror(line1, "P1", "202", null), mirror(line2, "P2", "201", null)));
        InternalShipmentStageServiceImpl service = new InternalShipmentStageServiceImpl(orderLineRepository,
                orderShipmentRepository, reservedShipmentItemRepository, reservedShipmentSettler,
                new InternalStageMarkets(List.of(acknowledgeAdapter())));

        InternalStageResult result = service.markInternal(List.of(1L, 2L));

        verify(orderShipmentRepository).setInternalStage(List.of(10L), "INTERNAL_PREPARING");
        assertThat(result.skippedOrderIds()).containsExactly("O2");
        assertThat(result.unsupported()).isEmpty();
    }

    @Test
    void collectInternalRows_readsPaidListFromDayBeforeOldestPaymentAndKeepsOnlyTargetBundles() {
        LocalDate today = LocalDate.now(SyncWindow.KST);
        OrderShipment bundle = shipment(10L, "D1", InternalShipmentStage.INTERNAL_PREPARING);
        bundle = bundle.toBuilder().order(order("O1").toBuilder()
                .orderedAt(today.minusDays(3).atTime(10, 0)).build()).build();
        Map<String, String> target = new LinkedHashMap<>();
        target.put("ordNo", "O1");
        target.put("ordPrdSeq", "1");
        target.put("dlvNo", "D1");
        target.put("ordQty", "2");
        target.put("rcvrNm", "홍길동");
        target.put("prdNm", "상품");
        Map<String, String> other = new LinkedHashMap<>();
        other.put("ordNo", "O9");
        other.put("ordPrdSeq", "1");
        other.put("dlvNo", "D9");
        other.put("ordQty", "1");
        given(elevenstOrderApi.listByMinute(eq(account), eq(ElevenstPaths.ORDERS_PAID), eq("ordPrdSeq"), any(), any()))
                .willReturn(List.of(target, other));
        ElevenstShippingLabelAdapter adapter = new ElevenstShippingLabelAdapter(coupangProperties, elevenstOrderApi,
                elevenstOrderUpserter);
        List<ShippingLabelRow> rows = new ArrayList<>();

        Set<String> found = adapter.collectInternalRows(account, List.of(bundle), rows);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(elevenstOrderApi).listByMinute(eq(account), eq(ElevenstPaths.ORDERS_PAID), eq("ordPrdSeq"),
                from.capture(), any());
        assertThat(from.getAllValues().get(0)).isEqualTo(today.minusDays(4).atStartOfDay());
        assertThat(found).containsExactly("D1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).quantity()).isEqualTo(2);
        assertThat(rows.get(0).shipmentBoxId()).isEqualTo("D1");
        verify(elevenstOrderUpserter, never()).upsertRows(any(), any(), any());   // R3 — not loaded into the order tables
    }

    @Test
    void sendReservedInvoices_shipsFirstInvoiceAndReportsFailurePerBundle() {
        OrderLine line1 = line(1L, "O1", shipment(10L, "D1", InternalShipmentStage.AWAITING_SHIPMENT), OrderStatus.PAID);
        OrderLine line2 = line(2L, "O2", shipment(20L, "D2", InternalShipmentStage.AWAITING_SHIPMENT), OrderStatus.PAID);
        lenient().doThrow(new IllegalStateException("송장 거절")).when(elevenstOrderActions)
                .ship(any(), eq("D2"), any(), any(), any());
        ElevenstShipmentInvoiceAdapter adapter = new ElevenstShipmentInvoiceAdapter(orderLineRepository,
                carrierCodeService, shipmentParcelRecorder, elevenstOrderActions, elevenstManualShipper,
                invoiceWriteBack);
        Map<Long, List<OrderLine>> linesByShipment = new LinkedHashMap<>();
        linesByShipment.put(10L, List.of(line1));
        linesByShipment.put(20L, List.of(line2));

        ReservedInvoiceResult result = adapter.sendReservedInvoices(List.of(
                new ReservedInvoice(10L, "O1", "00034", List.of("111", "222")),
                new ReservedInvoice(20L, "O2", "00034", List.of("333"))), linesByShipment);

        verify(elevenstOrderActions, times(1)).ship(any(), eq("D1"), eq("00034"), eq("111"), any());
        verify(invoiceWriteBack, times(1)).markDeparted(List.of("D1"), Map.of("D1", List.of(line1)));
        verify(invoiceWriteBack, never()).markDeparted(eq(List.of("D2")), any());
        assertThat(result.succeededShipmentIds()).containsExactly(10L);
        assertThat(result.failedReasons()).containsExactly(Map.entry(20L, "송장 거절"));
        verify(orderShipmentRepository, never()).setInternalStage(anyCollection(), any());
    }

    @Test
    void createReservation_readsCarrierCodeOnlyForMarketplacesInTheFile() {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        parsed.put("C1", List.of("111"));
        given(shipmentConfirmService.readInvoicesByOrderId(any())).willReturn(parsed);
        given(carrierCodeService.resolveDeliveryCompanyCode(Platform.COUPANG)).willReturn("CJGLS");
        MarketplaceAccount coupang = MarketplaceAccount.builder().id(8L).platform(Platform.COUPANG).build();
        Order order = Order.builder().id(2L).marketplaceAccount(coupang).platform(Platform.COUPANG)
                .externalOrderId("C1").build();
        OrderLine coupangLine = OrderLine.builder().id(5L).order(order)
                .orderShipment(shipment(50L, "B50", InternalShipmentStage.INTERNAL_PREPARING))
                .status(OrderStatus.PAID).orderQty(1).cancelQty(0).holdQty(0).build();
        given(orderLineRepository.findByExternalOrderId("C1")).willReturn(List.of(coupangLine));
        LocalDateTime future = LocalDateTime.now().plusDays(2);
        given(reservedShipmentRepository.save(any())).willReturn(ReservedShipment.builder().id(9L)
                .executeAt(future).nextRunAt(future).status(ReservedShipmentStatus.SCHEDULED).build());
        InternalStageMarkets markets = new InternalStageMarkets(List.of(
                new CoupangOrderAcknowledgeAdapter(null, null, null, null), acknowledgeAdapter()));

        reservedService(markets).create(
                new MockMultipartFile("file", "r.xlsx", "application/octet-stream", new byte[]{1}), future);

        verify(carrierCodeService, times(1)).resolveDeliveryCompanyCode(Platform.COUPANG);
        verify(carrierCodeService, never()).resolveDeliveryCompanyCode(Platform.ELEVENST);
    }

    @Test
    void list_rowCarriesTheMarketplaceOfItsBundle() {
        LocalDateTime soon = LocalDateTime.now().plusHours(1);
        ReservedShipment reservation = ReservedShipment.builder().id(9L).executeAt(soon).nextRunAt(soon)
                .status(ReservedShipmentStatus.SCHEDULED).retryCount(0).build();
        OrderShipment elevenstBundle = shipment(10L, "D1", InternalShipmentStage.AWAITING_SHIPMENT);
        OrderShipment coupangBundle = shipment(50L, "B50", InternalShipmentStage.AWAITING_SHIPMENT);
        given(reservedShipmentRepository.findForList(anyCollection(), any())).willReturn(List.of(reservation));
        given(reservedShipmentItemRepository.findByReservedShipment_IdIn(anyCollection())).willReturn(List.of(
                item(100L, reservation, elevenstBundle, "O1"), item(101L, reservation, coupangBundle, "C1")));
        MarketplaceAccount coupang = MarketplaceAccount.builder().id(8L).platform(Platform.COUPANG).build();
        OrderLine coupangLine = OrderLine.builder().id(5L)
                .order(Order.builder().id(2L).marketplaceAccount(coupang).platform(Platform.COUPANG)
                        .externalOrderId("C1").build())
                .orderShipment(coupangBundle).status(OrderStatus.PAID).orderQty(1).cancelQty(0).holdQty(0).build();
        given(orderLineRepository.findWithAccountByOrderShipment_IdIn(anyCollection())).willReturn(List.of(
                line(1L, "O1", elevenstBundle, OrderStatus.PAID), coupangLine));
        InternalStageMarkets markets = new InternalStageMarkets(List.of(
                new CoupangOrderAcknowledgeAdapter(null, null, null, null), acknowledgeAdapter()));

        List<ReservedShipmentRowResponse> rows = reservedService(markets).list();

        assertThat(rows).extracting(ReservedShipmentRowResponse::platform).containsExactly("ELEVENST", "COUPANG");
    }

    private ReservedShipmentServiceImpl reservedService(InternalStageMarkets markets) {
        return new ReservedShipmentServiceImpl(shipmentConfirmService, carrierCodeService, orderLineRepository,
                orderShipmentRepository, reservedShipmentRepository, reservedShipmentItemRepository,
                reservedShipmentSettler, internalShipmentStageService, reservedShipmentExecutor, markets);
    }

    private Order order(String orderId) {
        return Order.builder().id(1L).marketplaceAccount(account).platform(Platform.ELEVENST)
                .externalOrderId(orderId).build();
    }

    private OrderShipment shipment(Long id, String dlvNo, InternalShipmentStage stage) {
        return OrderShipment.builder().id(id).externalShipmentId(dlvNo).internalStage(stage).build();
    }

    private OrderLine line(Long id, String orderId, OrderShipment shipment, OrderStatus status) {
        return OrderLine.builder().id(id).order(order(orderId)).orderShipment(shipment).status(status)
                .orderQty(1).cancelQty(0).holdQty(0).build();
    }

    private ElevenstOrderLine mirror(OrderLine line, String prdNo, String platformStatus, String raw) {
        return ElevenstOrderLine.builder().id(line.getId()).orderLine(line).marketplaceAccount(account)
                .ordNo(line.getOrder().getExternalOrderId()).ordPrdSeq(String.valueOf(line.getId()))
                .prdNo(prdNo).platformStatus(platformStatus).raw(raw).build();
    }

    private ReservedShipmentItem item(Long id, ReservedShipment reservation, OrderShipment shipment, String orderId) {
        return ReservedShipmentItem.builder().id(id).reservedShipment(reservation).orderShipment(shipment)
                .externalOrderId(orderId).carrierCode("00034").invoiceNumbers("111")
                .progress(ReservedItemProgress.NONE).result(ReservedItemResult.PENDING).build();
    }
}
