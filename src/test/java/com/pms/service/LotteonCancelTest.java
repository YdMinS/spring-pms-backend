package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.LotteonAccountCredential;
import com.pms.domain.LotteonCancelRequest;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderCancelAction;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.request.LotteonCancelRejectRequest;
import com.pms.dto.request.LotteonDeliveryAppointmentRequest;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.dto.response.LotteonCancelRequestResponse;
import com.pms.dto.response.LotteonOrderChoicesResponse;
import com.pms.exception.BusinessException;
import com.pms.repository.LotteonCancelRequestRepository;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.OrderCancelActionRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.OrderCancelResult.CancelledLine;
import com.pms.service.OrderCancelResult.FailedLine;
import com.pms.service.OrderCancelResult.SkippedLine;
import com.pms.service.claim.ActionChoice;
import com.pms.service.claim.LotteonClaimSyncAdapter;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import com.pms.service.coupang.OrderSyncPreset;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import com.pms.service.coupang.SyncStatusRecorder;
import com.pms.service.lotteon.LotteonCancelRequestServiceImpl;
import com.pms.service.lotteon.LotteonCancelRequestUpserter;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderSellerServiceImpl;
import com.pms.service.lotteon.LotteonOrderSyncServiceImpl;
import com.pms.service.lotteon.LotteonOrderUpserter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FEATURE_2611_23 / 06 — LotteON cancels: [50] load (D8 · D24 · R21 · R22), the cancel-request list and approve /
 * reject (D24 · D71), seller direct cancel [225] (D25 · R26), shipping appointment [138] (D26 · R25), the sync step
 * (R23) and the code lists (D58).
 */
@ExtendWith(MockitoExtension.class)
class LotteonCancelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private LotteonCancelRequestRepository lotteonCancelRequestRepository;
    @Mock private LotteonOrderLineRepository lotteonOrderLineRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private OrderCancelActionRepository orderCancelActionRepository;
    @Mock private LotteonOrderApi lotteonOrderApi;
    @Mock private LotteonOrderUpserter lotteonOrderUpserter;
    @Mock private LotteonCancelRequestUpserter lotteonCancelRequestUpserter;
    @Mock private SyncStatusRecorder syncStatusRecorder;
    @Mock private LotteonClaimSyncAdapter lotteonClaimSyncAdapter;
    @Mock private CarrierCodeService carrierCodeService;

    private final MarketplaceAccount account = MarketplaceAccount.builder().id(9L).platform(Platform.LOTTEON)
            .seller(Seller.builder().id(3L).sellerName("판매자").build())
            .lotteonCredential(LotteonAccountCredential.builder().apiKey("KEY").build())
            .build();
    private final OrderShipment shipment = shipment();
    private final OrderLine line11 = line(11L, OrderStatus.PREPARING);
    private final LotteonOrderLine mirror11 = mirror(line11, "1", "12");

    private LotteonCancelRequestUpserter upserter() {
        return new LotteonCancelRequestUpserter(lotteonCancelRequestRepository, lotteonOrderLineRepository,
                orderLineRepository);
    }

    private LotteonCancelRequestServiceImpl cancelRequestService() {
        return new LotteonCancelRequestServiceImpl(lotteonCancelRequestRepository, lotteonCancelRequestUpserter,
                lotteonOrderApi);
    }

    private LotteonOrderCancelAdapter cancelAdapter() {
        return new LotteonOrderCancelAdapter(lotteonOrderApi, lotteonOrderLineRepository, orderCancelActionRepository);
    }

    private LotteonOrderSellerServiceImpl sellerService() {
        return new LotteonOrderSellerServiceImpl(orderLineRepository, lotteonOrderLineRepository, lotteonOrderApi,
                carrierCodeService);
    }

    private LotteonOrderSyncServiceImpl syncService() {
        return new LotteonOrderSyncServiceImpl(lotteonOrderApi, lotteonOrderUpserter, lotteonCancelRequestUpserter,
                syncStatusRecorder, lotteonClaimSyncAdapter, List.of());
    }

    @Test
    void upsertRows_storesRequestsAndSumsCompletedCancelsIntoTheLine() throws Exception {
        List<JsonNode> rows = List.of(
                JSON.readTree("""
                        {"clmNo":"C1","odNo":"OD1","odSeq":"1","procSeq":"2","orglProcSeq":"1","odTypCd":"20",
                         "odPrgsStepCd":"02","cnclQty":1,"clmRsnCd":"106","clmRsnCnts":"단순 변심",
                         "clmReqDttm":"20261101100000"}
                        """),
                JSON.readTree("""
                        {"clmNo":"C2","odNo":"OD1","odSeq":"1","odTypCd":"20","odPrgsStepCd":"21","cnclQty":2}
                        """),
                JSON.readTree("""
                        {"clmNo":"C3","odNo":"OD1","odSeq":"1","odTypCd":"50","odPrgsStepCd":"21","cnclQty":5}
                        """));
        given(lotteonCancelRequestRepository.findByMarketplaceAccount_IdAndClmNoAndOdSeq(eq(9L), any(), eq("1")))
                .willReturn(Optional.empty());
        given(lotteonCancelRequestRepository.save(any(LotteonCancelRequest.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(lotteonCancelRequestRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD1", "1"))
                .willReturn(List.of(request(1L, "C1", "02", 1, line11), request(2L, "C2", "21", 2, line11)));
        given(lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNoAndOdSeq(9L, "OD1", "1"))
                .willReturn(Optional.of(mirror11));

        int changed = upserter().upsertRows(account, rows);

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<LotteonCancelRequest> saved = ArgumentCaptor.forClass(LotteonCancelRequest.class);
        verify(lotteonCancelRequestRepository, times(2)).save(saved.capture());
        LotteonCancelRequest c1 = saved.getAllValues().get(0);
        assertThat(c1.getClmNo()).isEqualTo("C1");
        assertThat(c1.getPlatformStatus()).isEqualTo("02");
        assertThat(c1.getQuantity()).isEqualTo(1);
        assertThat(c1.getRequestedAt()).isEqualTo(LocalDateTime.of(2026, 11, 1, 10, 0));
        LotteonCancelRequest c2 = saved.getAllValues().get(1);
        assertThat(c2.getClmNo()).isEqualTo("C2");
        assertThat(c2.getPlatformStatus()).isEqualTo("21");
        assertThat(c2.getQuantity()).isEqualTo(2);
        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getCancelQty()).isEqualTo(2);
        assertThat(line.getValue().getHoldQty()).isZero();
    }

    @Test
    void upsertRows_keepsOurRejectionUntilLotteonClosesTheRequest() throws Exception {
        AtomicReference<LotteonCancelRequest> stored =
                new AtomicReference<>(request(1L, "C1", LotteonCancelRequest.STATUS_REJECTED, 1, line11));
        given(lotteonCancelRequestRepository.findByMarketplaceAccount_IdAndClmNoAndOdSeq(9L, "C1", "1"))
                .willAnswer(inv -> Optional.of(stored.get()));
        given(lotteonCancelRequestRepository.save(any(LotteonCancelRequest.class))).willAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        upserter().upsertRows(account, List.of(cancelRow("02")));
        assertThat(stored.get().getPlatformStatus()).isEqualTo("REJECTED");

        upserter().upsertRows(account, List.of(cancelRow("22")));
        assertThat(stored.get().getPlatformStatus()).isEqualTo("22");
    }

    @Test
    void listOpen_labelsTheReasonAsNameDashText() {
        LotteonCancelRequest named = request(1L, "C1", "02", 1, line11).toBuilder()
                .reasonCode("106").reasonText("단순 변심").requestedAt(LocalDateTime.of(2026, 11, 1, 10, 0)).build();
        LotteonCancelRequest unknown = request(2L, "C2", "02", 1, line11).toBuilder()
                .reasonCode("999").reasonText(null).requestedAt(LocalDateTime.of(2026, 11, 1, 9, 0)).build();
        given(lotteonCancelRequestRepository.findByOrderLine_IdAndPlatformStatus(11L, "02"))
                .willReturn(List.of(named, unknown));

        List<LotteonCancelRequestResponse> open = cancelRequestService().listOpen(11L);

        assertThat(open).hasSize(2);
        assertThat(open.get(0).reason()).isEqualTo("사유 코드 999");
        assertThat(open.get(1).reason()).isEqualTo("구매의사 없어짐 — 단순 변심");
    }

    @Test
    void approve_sendsTheRequestReasonAndMarksItCompleted() {
        given(lotteonCancelRequestRepository.findWithAccountById(5L)).willReturn(Optional.of(openRequest()));

        cancelRequestService().approve(5L);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(lotteonOrderApi).approveCancelRequest(eq(account), body.capture());
        assertThat(body.getValue()).isEqualTo(Map.of("odNo", "OD1", "clmNo", "C1", "itemList",
                List.of(Map.of("odSeq", "1", "procSeq", "2", "orglProcSeq", "1", "slrRsnCd", "106"))));
        verify(lotteonCancelRequestUpserter, times(1)).markApproved(5L);
    }

    @Test
    void approve_closedRequestIsConflict() {
        given(lotteonCancelRequestRepository.findWithAccountById(5L))
                .willReturn(Optional.of(openRequest().toBuilder().platformStatus("21").build()));

        assertThatThrownBy(() -> cancelRequestService().approve(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 처리된 접수입니다")
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(lotteonOrderApi);
    }

    @Test
    void reject_unlistedReasonIsRefusedBeforeCalling() {
        given(lotteonCancelRequestRepository.findWithAccountById(5L)).willReturn(Optional.of(openRequest()));

        assertThatThrownBy(() -> cancelRequestService().reject(5L, new LotteonCancelRejectRequest("999", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("거부 사유를 선택하세요");
        verifyNoInteractions(lotteonOrderApi);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reject_sendsReasonAndTextAndMarksRejected() {
        given(lotteonCancelRequestRepository.findWithAccountById(5L)).willReturn(Optional.of(openRequest()));

        cancelRequestService().reject(5L, new LotteonCancelRejectRequest("902", "고객과 통화함"));

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(lotteonOrderApi).rejectCancelRequest(eq(account), body.capture());
        Map<String, Object> item = ((List<Map<String, Object>>) body.getValue().get("itemList")).get(0);
        assertThat(item.get("slrRsnCd")).isEqualTo("902");
        assertThat(item.get("slrRsnCnts")).isEqualTo("고객과 통화함");
        verify(lotteonCancelRequestUpserter, times(1)).markRejected(5L);
    }

    @Test
    void sellerCancel_sendsTheWholeLineAndRecordsTheAttempt() {
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(mirror11));
        List<CancelledLine> cancelled = new ArrayList<>();
        List<FailedLine> failed = new ArrayList<>();

        runCancel(cancelled, failed);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(lotteonOrderApi).sellerCancel(eq(account), body.capture());
        assertThat(body.getValue()).isEqualTo(Map.of("odNo", "OD1", "itemList",
                List.of(Map.of("odSeq", "1", "procSeq", "1", "slrRsnCd", "111"))));
        assertThat(failed).isEmpty();
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.get(0).cancelledQty()).isZero();
        assertThat(cancelled.get(0).receiptType()).isEqualTo("LOTTEON_SELLER_CANCEL");
        OrderCancelAction history = savedHistory();
        assertThat(history.isSucceeded()).isTrue();
        assertThat(history.getPlatformReasonCode()).isEqualTo("111");
        assertThat(history.getStatusAtSend()).isEqualTo("12");
        assertThat(history.getReceiptType()).isEqualTo("LOTTEON_SELLER_CANCEL");
        assertThat(history.getReason()).isNull();
    }

    @Test
    void sellerCancel_lotteonRefusalIsAFailedLineWithItsText() {
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(mirror11));
        doThrow(new BusinessException("주문의 상태를 확인해 주세요.", HttpStatus.BAD_GATEWAY))
                .when(lotteonOrderApi).sellerCancel(eq(account), anyMap());
        List<CancelledLine> cancelled = new ArrayList<>();
        List<FailedLine> failed = new ArrayList<>();

        runCancel(cancelled, failed);

        assertThat(cancelled).isEmpty();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).code()).isEqualTo("ERROR");
        assertThat(failed.get(0).message()).isEqualTo("주문의 상태를 확인해 주세요.");
        assertThat(savedHistory().isSucceeded()).isFalse();
    }

    @Test
    void sellerCancel_unlistedReasonIsRefused() {
        OrderCancelRequest request = new OrderCancelRequest(List.of(new OrderCancelRequest.Line(11L, 3)), null,
                "999", null);

        assertThatThrownBy(() -> cancelAdapter().checkReason(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("취소 사유를 선택하세요");
    }

    @Test
    void deliveryAppointment_sendsEveryOpenLineOfTheOrderAtTheEndOfTheDay() {
        OrderLine paid = line(11L, OrderStatus.PAID);
        OrderLine preparing = line(12L, OrderStatus.PREPARING);
        OrderLine shipped = line(13L, OrderStatus.SHIPPED);
        given(orderLineRepository.findWithAccountAndSellerById(11L)).willReturn(Optional.of(paid));
        given(orderLineRepository.findByExternalOrderId("OD1")).willReturn(List.of(shipped, preparing, paid));
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList())).willReturn(List.of(
                mirror(paid, "1", "11"), mirror(preparing, "2", "12"), mirror(shipped, "3", "13")));

        sellerService().deliveryAppointment(11L,
                new LotteonDeliveryAppointmentRequest(LocalDate.of(2026, 11, 20), "PD_DL", "ORD_INC_DL"));

        ArgumentCaptor<List<Map<String, Object>>> body = listCaptor();
        verify(lotteonOrderApi, times(1)).informAppointment(eq(account), body.capture());
        List<Map<String, Object>> lines = body.getValue();
        assertThat(lines).extracting(l -> l.get("odSeq")).containsExactly("1", "2");
        assertThat(lines).allSatisfy(l -> {
            assertThat(l.get("sndDlRsnCd")).isEqualTo("PD_DL");
            assertThat(l.get("sndDlRsnCnts")).isEqualTo("ORD_INC_DL");
            assertThat(l.get("sndAgrdDttm")).isEqualTo("20261120235959");
        });
    }

    @Test
    void deliveryAppointment_makingDelayNeedsADetail() {
        OrderLine paid = line(11L, OrderStatus.PAID);
        given(orderLineRepository.findWithAccountAndSellerById(11L)).willReturn(Optional.of(paid));
        given(lotteonOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(paid, "1", "11")));

        assertThatThrownBy(() -> sellerService().deliveryAppointment(11L,
                new LotteonDeliveryAppointmentRequest(LocalDate.of(2026, 11, 20), "PD_DL", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("제작지연은 상세 사유를 선택하세요");
        verify(lotteonOrderApi, never()).informAppointment(any(), anyList());
    }

    @Test
    void sync_loadsCancelsAfterOrdersOverFourteenDays() {
        given(lotteonOrderUpserter.upsertRows(eq(account), anyList())).willReturn(UpsertCount.empty());
        given(lotteonOrderApi.deliveryOrders(eq(account), any(), any())).willReturn(List.of());
        given(lotteonOrderApi.progressStates(eq(account), any(), any())).willReturn(List.of());
        given(lotteonOrderApi.cancelRequests(eq(account), any(), any())).willReturn(List.of());
        given(lotteonCancelRequestUpserter.upsertRows(eq(account), anyList())).willReturn(1);

        OrderSyncResult result = syncService().sync(account, OrderSyncPreset.QUICK, false);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(lotteonOrderApi, times(1)).cancelRequests(eq(account), from.capture(), to.capture());
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofDays(14));
        assertThat(result.canceledUpdated()).isEqualTo(1);
        verify(syncStatusRecorder, times(1)).recordSuccess(9L);
    }

    @Test
    void sync_cancelFailureIsPartialOnAManualRound() {
        given(lotteonOrderUpserter.upsertRows(eq(account), anyList())).willReturn(UpsertCount.empty());
        given(lotteonOrderApi.cancelRequests(eq(account), any(), any()))
                .willThrow(new BusinessException("조회 실패", HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> syncService().sync(account, OrderSyncPreset.QUICK, false))
                .isInstanceOf(BusinessException.class).hasMessage("조회 실패");
        assertThatThrownBy(() -> syncService().sync(account, OrderSyncPreset.QUICK, true))
                .isInstanceOf(BusinessException.class).hasMessage("조회 실패");

        verify(syncStatusRecorder, times(1))
                .recordPartial(eq(9L), startsWith("취소 반영 실패 — "), eq(true), eq(false));
        verify(syncStatusRecorder, never()).recordSuccess(any());
    }

    @Test
    void choices_listsEveryLotteonReason() {
        LotteonOrderChoicesResponse choices = sellerService().choices();

        assertThat(choices.cancelReasons()).hasSize(5);
        assertThat(choices.cancelRejectReasons()).hasSize(3);
        assertThat(choices.appointmentReasons()).hasSize(4);
        assertThat(choices.appointmentDetailReasons()).hasSize(2);
        assertThat(choices.holdReasons()).hasSize(5);
        assertThat(choices.appointmentReasons().get(3)).isEqualTo(new ActionChoice("PD_DL", "제작지연"));
        assertThat(choices.holdReasons().get(2)).isEqualTo(new ActionChoice("04", "상품 훼손"));
    }

    private void runCancel(List<CancelledLine> cancelled, List<FailedLine> failed) {
        OrderCancelRequest request = new OrderCancelRequest(List.of(new OrderCancelRequest.Line(11L, 3)), null,
                "111", null);
        LotteonOrderCancelAdapter adapter = cancelAdapter();
        adapter.checkReason(request);
        OrderCancelAdapter.Batch batch = adapter.begin(request, List.of(line11));
        List<SkippedLine> skipped = new ArrayList<>();
        List<SkippedLine> unsupported = new ArrayList<>();
        batch.add(line11, 3, skipped, unsupported);
        batch.send(cancelled, failed);
        assertThat(skipped).isEmpty();
        assertThat(unsupported).isEmpty();
    }

    private OrderCancelAction savedHistory() {
        ArgumentCaptor<OrderCancelAction> history = ArgumentCaptor.forClass(OrderCancelAction.class);
        verify(orderCancelActionRepository, times(1)).save(history.capture());
        return history.getValue();
    }

    private LotteonCancelRequest openRequest() {
        return LotteonCancelRequest.builder().id(5L).marketplaceAccount(account).clmNo("C1").odNo("OD1").odSeq("1")
                .procSeq("2").orglProcSeq("1").quantity(1).reasonCode("106").platformStatus("02").build();
    }

    private LotteonCancelRequest request(Long id, String clmNo, String status, int quantity, OrderLine line) {
        return LotteonCancelRequest.builder().id(id).marketplaceAccount(account).orderLine(line).clmNo(clmNo)
                .odNo("OD1").odSeq("1").quantity(quantity).platformStatus(status).build();
    }

    private static JsonNode cancelRow(String step) throws Exception {
        return JSON.readTree("{\"clmNo\":\"C1\",\"odNo\":\"OD1\",\"odSeq\":\"1\",\"odTypCd\":\"20\","
                + "\"odPrgsStepCd\":\"" + step + "\",\"cnclQty\":1}");
    }

    private OrderShipment shipment() {
        Order order = Order.builder().id(100L).marketplaceAccount(account).platform(Platform.LOTTEON)
                .externalOrderId("OD1").build();
        return OrderShipment.builder().id(10L).order(order).externalShipmentId("OD1-1").build();
    }

    private OrderLine line(Long id, OrderStatus status) {
        return OrderLine.builder().id(id).order(shipment.getOrder()).orderShipment(shipment).status(status)
                .itemName("상품").orderQty(3).cancelQty(0).holdQty(0).build();
    }

    private LotteonOrderLine mirror(OrderLine line, String odSeq, String platformStatus) {
        return LotteonOrderLine.builder().id(line.getId()).orderLine(line).marketplaceAccount(account).odNo("OD1")
                .odSeq(odSeq).procSeq("1").platformStatus(platformStatus).sitmNo("LO" + odSeq).build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
