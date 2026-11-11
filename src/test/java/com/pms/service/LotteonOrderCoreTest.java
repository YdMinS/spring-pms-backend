package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.LotteonProperties;
import com.pms.domain.LotteonAccountCredential;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.exception.BusinessException;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.OrderAcknowledgeAdapter.AcknowledgeOutcome;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.coupang.LotteonOrderSyncAdapter;
import com.pms.service.coupang.OrderSyncPreset;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonCancelRequestUpserter;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderSyncService;
import com.pms.service.lotteon.LotteonOrderSyncServiceImpl;
import com.pms.service.lotteon.LotteonOrderUpserter;
import com.pms.service.lotteon.LotteonPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FEATURE_2611_23 — LotteON order core: load (D6 ~ D9 · D12 · D13 · R5 · R7 · R8), sync windows (D11 · D65),
 * [210] receipt (D15 · D16 · R10), label sheet (D20 · R11) and internal sheet (D49 ②).
 */
@ExtendWith(MockitoExtension.class)
class LotteonOrderCoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private OrderRepository orderRepository;
    @Mock private OrderShipmentRepository orderShipmentRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private LotteonOrderLineRepository lotteonOrderLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private LotteonOrderApi lotteonOrderApi;
    @Mock private LotteonApiClient lotteonApiClient;
    @Mock private LotteonOrderUpserter lotteonOrderUpserter;
    @Mock private LotteonCancelRequestUpserter lotteonCancelRequestUpserter;
    @Mock private LotteonOrderSyncService lotteonOrderSyncService;
    @Mock private SyncStatusRecorder syncStatusRecorder;

    private final MarketplaceAccount account = MarketplaceAccount.builder().id(9L).platform(Platform.LOTTEON)
            .seller(Seller.builder().id(3L).sellerName("판매자").build())
            .lotteonCredential(LotteonAccountCredential.builder().apiKey("KEY").build())
            .build();

    private LotteonOrderUpserter upserter() {
        return new LotteonOrderUpserter(orderRepository, orderShipmentRepository, orderLineRepository,
                lotteonOrderLineRepository, productListingOptionRepository);
    }

    private LotteonOrderAcknowledgeAdapter acknowledgeAdapter() {
        return new LotteonOrderAcknowledgeAdapter(lotteonOrderApi, lotteonOrderLineRepository, orderLineRepository);
    }

    private LotteonShippingLabelAdapter labelAdapter() {
        return new LotteonShippingLabelAdapter(lotteonOrderApi, lotteonOrderUpserter, lotteonOrderLineRepository);
    }

    @Test
    void upsertRows_createsOrderBundleLineAndMirrorFromNewOutboundRow() throws Exception {
        JsonNode row = JSON.readTree("""
                {"odNo":"OD1","odSeq":1,"procSeq":1,"dvRtrvDvsCd":"DV","odTypCd":"10","odPrgsStepCd":"11",
                 "cmbnDvPsbYn":"Y","cmbnDvGrpNo":"777","spdNm":"상품","sitmNm":"빨강","eitmNo":"55",
                 "odQty":2,"slPrc":5000,"slAmt":10000,"fvrAmtSum":1000,"prSfcoShrAmtSum":300,"dvCst":3000,
                 "odCmptDttm":"20261110093000","odrNm":"홍길동","dvpCustNm":"김수취","dvpMphnNo":"010-1",
                 "dvpStnmZipAddr":"서울","dvMsg":"문앞"}
                """);
        ProductListingOption option = ProductListingOption.builder().id(55L).build();
        given(lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD1", "1"))
                .willReturn(Optional.empty());
        given(orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(9L, "OD1")).willReturn(Optional.empty());
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(orderShipmentRepository.findByOrder_IdAndExternalShipmentId(any(), eq("OD1-G777")))
                .willReturn(Optional.empty());
        given(orderShipmentRepository.save(any(OrderShipment.class))).willAnswer(inv -> inv.getArgument(0));
        given(orderLineRepository.save(any(OrderLine.class))).willAnswer(inv -> inv.getArgument(0));
        given(productListingOptionRepository.findLotteonOrderOption(55L, 9L)).willReturn(Optional.of(option));

        UpsertCount count = upserter().upsertRows(account, List.of(row));

        assertThat(count).isEqualTo(new UpsertCount(1, 0));
        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(order.capture());
        assertThat(order.getValue().getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 30));
        assertThat(order.getValue().getReceiverName()).isEqualTo("김수취");
        ArgumentCaptor<OrderShipment> shipment = ArgumentCaptor.forClass(OrderShipment.class);
        verify(orderShipmentRepository).save(shipment.capture());
        assertThat(shipment.getValue().getExternalShipmentId()).isEqualTo("OD1-G777");
        assertThat(shipment.getValue().getShippingFee()).isEqualByComparingTo(new BigDecimal("3000"));
        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(line.getValue().getItemName()).isEqualTo("상품 / 빨강");
        assertThat(line.getValue().getDiscountAmount()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(line.getValue().getPlatformDiscountAmount()).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(line.getValue().getProductListingOption().getId()).isEqualTo(55L);
        ArgumentCaptor<LotteonOrderLine> mirror = ArgumentCaptor.forClass(LotteonOrderLine.class);
        verify(lotteonOrderLineRepository).save(mirror.capture());
        assertThat(mirror.getValue().getProcSeq()).isEqualTo("1");
        assertThat(mirror.getValue().getPlatformStatus()).isEqualTo("11");
        assertThat(mirror.getValue().getRaw()).doesNotContain("010-1", "서울", "문앞", "홍길동");
    }

    @Test
    void upsertRows_movesStatusForwardOnlyAndSkipsStepsNotLoaded() throws Exception {
        OrderLine stored = OrderLine.builder().id(1L).status(OrderStatus.PREPARING)
                .orderQty(1).cancelQty(0).holdQty(0).build();
        LotteonOrderLine storedMirror = LotteonOrderLine.builder().id(1L).orderLine(stored).marketplaceAccount(account)
                .odNo("OD1").odSeq("1").procSeq("1").platformStatus("12").build();
        given(lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD1", "1"))
                .willReturn(Optional.of(storedMirror));
        given(orderLineRepository.save(any(OrderLine.class))).willAnswer(inv -> inv.getArgument(0));
        List<JsonNode> rows = List.of(
                JSON.readTree("""
                        {"odNo":"OD1","odSeq":"1","procSeq":"2","dvRtrvDvsCd":"DV","odTypCd":"10",
                         "odPrgsStepCd":"11","odQty":"1"}"""),
                JSON.readTree("""
                        {"odNo":"OD2","odSeq":"1","procSeq":"1","dvRtrvDvsCd":"DV","odTypCd":"10",
                         "odPrgsStepCd":"21","odQty":"1"}"""),
                JSON.readTree("""
                        {"odNo":"OD3","odSeq":"1","procSeq":"1","dvRtrvDvsCd":"RTRV","odTypCd":"40",
                         "odPrgsStepCd":"11","odQty":"1"}"""));

        UpsertCount count = upserter().upsertRows(account, rows);

        assertThat(count).isEqualTo(new UpsertCount(0, 1));
        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(OrderStatus.PREPARING);
        ArgumentCaptor<LotteonOrderLine> mirror = ArgumentCaptor.forClass(LotteonOrderLine.class);
        verify(lotteonOrderLineRepository).save(mirror.capture());
        assertThat(mirror.getValue().getPlatformStatus()).isEqualTo("12");
        verify(lotteonOrderLineRepository, never()).findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD2", "1");
        verify(lotteonOrderLineRepository, never()).findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD3", "1");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void sync_reads209Then140OverFourteenOneDayWindows() {
        given(lotteonOrderApi.deliveryOrders(eq(account), any(), any())).willReturn(List.of());
        given(lotteonOrderApi.progressStates(eq(account), any(), any())).willReturn(List.of());
        given(lotteonOrderUpserter.upsertRows(eq(account), anyList())).willReturn(UpsertCount.empty());
        LotteonOrderSyncServiceImpl service = new LotteonOrderSyncServiceImpl(lotteonOrderApi, lotteonOrderUpserter,
                lotteonCancelRequestUpserter, syncStatusRecorder, List.of(), List.of());

        service.sync(account, OrderSyncPreset.QUICK, false);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(lotteonOrderApi, times(14)).deliveryOrders(eq(account), from.capture(), to.capture());
        verify(lotteonOrderApi, times(14)).progressStates(eq(account), any(), any());
        assertThat(to.getAllValues().get(0)).isEqualTo(from.getAllValues().get(0).plusDays(1).minusSeconds(1));
        verify(syncStatusRecorder, times(1)).recordSuccess(9L);
    }

    @Test
    void syncAdapter_scheduledRoundFollowsTheOutboundSwitch() {
        LotteonProperties properties = new LotteonProperties();
        LotteonOrderSyncAdapter adapter = new LotteonOrderSyncAdapter(lotteonOrderSyncService, properties);

        assertThat(adapter.platform()).isEqualTo(Platform.LOTTEON);
        properties.setOutboundEnabled(true);
        assertThat(adapter.runsInScheduledRound()).isTrue();
        properties.setOutboundEnabled(false);
        assertThat(adapter.runsInScheduledRound()).isFalse();
        verifyNoInteractions(lotteonOrderSyncService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void informIfComplete_postsYesLinesAndRejectsAFailedResultCode() throws Exception {
        given(lotteonApiClient.post(eq(LotteonPaths.IF_COMPLETE), any(), eq("KEY"))).willReturn(
                JSON.readTree("{\"returnCode\":\"0000\",\"data\":{\"rsltCd\":\"0000\"}}"),
                JSON.readTree("{\"returnCode\":\"0000\",\"data\":{\"rsltCd\":\"3001\",\"rsltMsg\":\"주문번호 없음\"}}"));
        LotteonOrderApi api = new LotteonOrderApi(lotteonApiClient);
        List<Map<String, Object>> lines = List.of(Map.of("dvRtrvDvsCd", "DV", "odNo", "OD1", "odSeq", "1",
                "procSeq", "1", "ifCplYN", "Y"));

        api.informIfComplete(account, lines);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(lotteonApiClient).post(eq(LotteonPaths.IF_COMPLETE), body.capture(), eq("KEY"));
        assertThat(((Map<String, Object>) body.getValue()).keySet()).containsExactly("ifCompleteList");
        assertThatThrownBy(() -> api.informIfComplete(account, lines))
                .isInstanceOf(BusinessException.class)
                .hasMessage("주문번호 없음");
    }

    @Test
    void acknowledge_sendsLinesAndReportsBundleOfFailedCallOnce() {
        OrderShipment shipment = shipment("OD1", "OD1-1");
        OrderLine line1 = line(1L, shipment);
        OrderLine line2 = line(2L, shipment);
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(line1, "1"), mirror(line2, "2")));
        doThrow(new BusinessException("거절", HttpStatus.BAD_GATEWAY))
                .when(lotteonOrderApi).informIfComplete(eq(account), anyList());
        LotteonOrderAcknowledgeAdapter adapter = acknowledgeAdapter();

        AcknowledgeOutcome outcome = adapter.acknowledge(List.of(line1, line2));

        assertThat(adapter.internalStage()).isTrue();
        assertThat(outcome.targets()).isEqualTo(2);
        assertThat(outcome.succeeded()).isZero();
        assertThat(outcome.failed()).containsExactly(new FailedBox("OD1-1", "ERROR", "거절"));
        assertThat(outcome.succeededBoxIds()).isEmpty();
        verify(orderLineRepository, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acknowledge_reportsLineCountsOnSuccess() {
        OrderShipment shipment = shipment("OD1", "OD1-1");
        OrderLine line1 = line(1L, shipment);
        OrderLine line2 = line(2L, shipment);
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(line1, "1"), mirror(line2, "2")));

        AcknowledgeOutcome outcome = acknowledgeAdapter().acknowledge(List.of(line1, line2));

        assertThat(outcome.targets()).isEqualTo(2);
        assertThat(outcome.succeeded()).isEqualTo(2);
        assertThat(outcome.failed()).isEmpty();
        assertThat(outcome.succeededBoxIds()).containsExactly("OD1-1");
        ArgumentCaptor<List<OrderLine>> saved = ArgumentCaptor.forClass(List.class);
        verify(orderLineRepository, times(1)).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2)
                .allSatisfy(line -> assertThat(line.getStatus()).isEqualTo(OrderStatus.PREPARING));
    }

    @Test
    void collectRows_buildsSheetFromPreparingLinesWithCancelSubtracted() throws Exception {
        JsonNode rowA = JSON.readTree("""
                {"odNo":"OD1","odSeq":"1","dvRtrvDvsCd":"DV","odTypCd":"10","odPrgsStepCd":"12","odQty":"3",
                 "dvpMphnNo":"","dvpTelNo":"02-1","dvpStnmZipAddr":"서울 중구","dvpStnmDtlAddr":"1층"}""");
        JsonNode rowB = JSON.readTree("""
                {"odNo":"OD2","odSeq":"1","dvRtrvDvsCd":"DV","odTypCd":"10","odPrgsStepCd":"11","odQty":"1"}""");
        given(lotteonOrderApi.progressStates(eq(account), any(), any()))
                .willReturn(List.of(rowA, rowB), List.of());
        OrderLine stored = OrderLine.builder().id(1L).orderQty(3).cancelQty(1).holdQty(0).build();
        given(lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD1", "1"))
                .willReturn(Optional.of(LotteonOrderLine.builder().orderLine(stored).odNo("OD1").odSeq("1").build()));

        List<ShippingLabelRow> rows = labelAdapter().collectRows(account);

        assertThat(rows).hasSize(1);
        ShippingLabelRow row = rows.get(0);
        assertThat(row.receiverPhone()).isEqualTo("02-1");
        assertThat(row.address()).isEqualTo("서울 중구 1층");
        assertThat(row.quantity()).isEqualTo(2);
        assertThat(row.shipmentBoxId()).isEqualTo("OD1-1");
        assertThat(row.platform()).isEqualTo("LOTTEON");
        verify(lotteonOrderApi, times(14)).progressStates(eq(account), any(), any());
    }

    @Test
    void collectInternalRows_keepsOutboundAndPreparingLinesOfTargetBundlesOnly() throws Exception {
        OrderShipment target = shipment("OD1", "OD1-1");
        given(lotteonOrderApi.progressStates(eq(account), any(), any())).willReturn(List.of(
                JSON.readTree("""
                        {"odNo":"OD1","odSeq":"1","dvRtrvDvsCd":"DV","odTypCd":"10","odPrgsStepCd":"11",
                         "odQty":"1"}"""),
                JSON.readTree("""
                        {"odNo":"OD2","odSeq":"1","dvRtrvDvsCd":"DV","odTypCd":"10","odPrgsStepCd":"11",
                         "odQty":"1"}"""),
                JSON.readTree("""
                        {"odNo":"OD1","odSeq":"3","dvRtrvDvsCd":"DV","odTypCd":"10","odPrgsStepCd":"13",
                         "odQty":"1"}""")), List.of());
        given(lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(any(), any(), any()))
                .willReturn(Optional.empty());
        List<ShippingLabelRow> rows = new ArrayList<>();

        Set<String> found = labelAdapter().collectInternalRows(account, List.of(target), rows);

        assertThat(found).containsExactly("OD1-1");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).orderId()).isEqualTo("OD1");
        verify(lotteonOrderUpserter, never()).upsertRows(any(), anyList());
    }

    private OrderShipment shipment(String orderId, String bundleId) {
        Order order = Order.builder().id(100L).marketplaceAccount(account).platform(Platform.LOTTEON)
                .externalOrderId(orderId).build();
        return OrderShipment.builder().id(10L).order(order).externalShipmentId(bundleId).build();
    }

    private OrderLine line(Long id, OrderShipment shipment) {
        return OrderLine.builder().id(id).order(shipment.getOrder()).orderShipment(shipment)
                .status(OrderStatus.PAID).orderQty(1).cancelQty(0).holdQty(0).build();
    }

    private LotteonOrderLine mirror(OrderLine line, String odSeq) {
        return LotteonOrderLine.builder().id(line.getId()).orderLine(line).marketplaceAccount(account)
                .odNo(line.getOrder().getExternalOrderId()).odSeq(odSeq).procSeq("1").platformStatus("11").build();
    }
}
