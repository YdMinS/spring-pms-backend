package com.pms.service.reservation;

import com.pms.domain.InternalShipmentStage;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ReservedItemProgress;
import com.pms.domain.ReservedItemResult;
import com.pms.domain.ReservedRunKind;
import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentItem;
import com.pms.domain.ReservedShipmentStatus;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ReservedShipmentItemRepository;
import com.pms.repository.ReservedShipmentRepository;
import com.pms.service.OrderAcknowledgeService;
import com.pms.service.ReservedInvoiceResult;
import com.pms.service.ShipmentConfirmService;
import com.pms.service.coupang.OrderRefreshResult;
import com.pms.service.coupang.OrderRefreshService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservedShipmentExecutorTest {

    @Mock private ReservedShipmentRepository reservedShipmentRepository;
    @Mock private ReservedShipmentItemRepository reservedShipmentItemRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private OrderShipmentRepository orderShipmentRepository;
    @Mock private MarketplaceAccountRepository marketplaceAccountRepository;
    @Mock private OrderRefreshService orderRefreshService;
    @Mock private OrderAcknowledgeService orderAcknowledgeService;
    @Mock private ShipmentConfirmService shipmentConfirmService;
    @InjectMocks private ReservedShipmentExecutor executor;

    private final OrderShipment shipment = OrderShipment.builder().id(10L).externalShipmentId("B10")
            .internalStage(InternalShipmentStage.AWAITING_SHIPMENT).build();

    @Test
    void run_marksCancelledWhenFullyCancelled() {
        startWith(reservation(0), item(ReservedItemProgress.NONE, ReservedItemResult.PENDING));
        refreshOk();
        linesAre(line(OrderStatus.PAID, 1, 1));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.CANCELLED);
        verify(orderShipmentRepository).clearInternalStage(List.of(10L));
        verify(orderAcknowledgeService, never()).acknowledgeForReservation(anyList());
        assertThat(lastSavedReservation().getStatus()).isEqualTo(ReservedShipmentStatus.DONE);
    }

    @Test
    void run_marksCancelledWhenCoupangReturnsNoBox() {
        startWith(reservation(0), item(ReservedItemProgress.NONE, ReservedItemResult.PENDING));
        given(orderRefreshService.refresh(any()))
                .willReturn(new OrderRefreshResult(1, 0, List.of("O1"), List.of(), List.of(), List.of()));
        linesAre(line(OrderStatus.PAID, 1, 0));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.CANCELLED);    // D17 ① — FAILED 아님
        assertThat(lastSavedItem().getFailureReason()).isNull();
        verify(orderShipmentRepository).clearInternalStage(List.of(10L));
        verify(orderAcknowledgeService, never()).acknowledgeForReservation(anyList());
        assertThat(lastSavedReservation().getStatus()).isEqualTo(ReservedShipmentStatus.DONE);
        assertThat(lastSavedReservation().getRetryCount()).isZero();                         // 재시도 없음
    }

    @Test
    void run_releasesWhenPartiallyCancelled() {
        startWith(reservation(0), item(ReservedItemProgress.NONE, ReservedItemResult.PENDING));
        refreshOk();
        linesAre(line(OrderStatus.PAID, 2, 1));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.RELEASED);
        assertThat(lastSavedItem().getFailureReason()).isEqualTo(ReservedShipmentExecutor.PARTIAL_CANCEL_MESSAGE);
        verify(orderShipmentRepository).setInternalStage(List.of(10L), "INTERNAL_PREPARING");
        assertThat(lastSavedReservation().getStatus()).isEqualTo(ReservedShipmentStatus.CANCELLED);
    }

    @Test
    void run_marksExternalWhenAlreadyShipped() {
        startWith(reservation(0), item(ReservedItemProgress.NONE, ReservedItemResult.PENDING));
        refreshOk();
        linesAre(line(OrderStatus.SHIPPED, 1, 0));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.EXTERNAL);
        verify(shipmentConfirmService, never()).sendReservedInvoices(anyList());
    }

    @Test
    void run_skipsAcknowledgeWhenAlreadyPreparing() {
        startWith(reservation(0), item(ReservedItemProgress.NONE, ReservedItemResult.PENDING));
        refreshOk();
        linesAre(line(OrderStatus.PREPARING, 1, 0));
        given(shipmentConfirmService.sendReservedInvoices(anyList()))
                .willReturn(new ReservedInvoiceResult(List.of(10L), Map.of()));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        verify(orderAcknowledgeService, never()).acknowledgeForReservation(anyList());
        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.SUCCEEDED);
        assertThat(lastSavedItem().getProgress()).isEqualTo(ReservedItemProgress.INVOICED);
    }

    @Test
    void run_resumesFromInvoiceWhenAcknowledged() {
        startWith(reservation(1), item(ReservedItemProgress.ACKNOWLEDGED, ReservedItemResult.FAILED));
        linesAre(line(OrderStatus.PREPARING, 1, 0));
        given(shipmentConfirmService.sendReservedInvoices(anyList()))
                .willReturn(new ReservedInvoiceResult(List.of(10L), Map.of()));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        verify(orderRefreshService, never()).refresh(any());
        verify(orderAcknowledgeService, never()).acknowledgeForReservation(anyList());
        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.SUCCEEDED);
    }

    @Test
    void run_schedulesRetryTwentyMinutesLater() {
        startWith(reservation(0), item(ReservedItemProgress.ACKNOWLEDGED, ReservedItemResult.PENDING));
        linesAre(line(OrderStatus.PREPARING, 1, 0));
        given(shipmentConfirmService.sendReservedInvoices(anyList()))
                .willReturn(new ReservedInvoiceResult(List.of(), Map.of(10L, "E: 송장 형식 오류")));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        ReservedShipment saved = lastSavedReservation();
        assertThat(saved.getStatus()).isEqualTo(ReservedShipmentStatus.SCHEDULED);
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getNextRunAt()).isEqualTo(saved.getLastRunAt().plusMinutes(20));
        assertThat(lastSavedItem().getFailureReason()).isEqualTo("송장 등록 실패: E: 송장 형식 오류");
    }

    @Test
    void run_stopsAfterThirdFailedRun() {
        startWith(reservation(2), item(ReservedItemProgress.ACKNOWLEDGED, ReservedItemResult.FAILED));
        linesAre(line(OrderStatus.PREPARING, 1, 0));
        given(shipmentConfirmService.sendReservedInvoices(anyList()))
                .willReturn(new ReservedInvoiceResult(List.of(), Map.of(10L, "E: 실패")));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        assertThat(lastSavedReservation().getStatus()).isEqualTo(ReservedShipmentStatus.STOPPED);
        assertThat(lastSavedReservation().getRetryCount()).isEqualTo(3);
    }

    @Test
    void run_skipsAcknowledgeOnRetryWhenLocalAlreadyPreparing() {
        startWith(reservation(1), item(ReservedItemProgress.CANCEL_CHECKED, ReservedItemResult.FAILED));
        linesAre(line(OrderStatus.PREPARING, 1, 0));                              // WING 에서 발주처리됨 — 동기화가 반영
        given(shipmentConfirmService.sendReservedInvoices(anyList()))
                .willReturn(new ReservedInvoiceResult(List.of(10L), Map.of()));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        verify(orderRefreshService, never()).refresh(any());                     // ①은 이미 지났다
        verify(orderAcknowledgeService, never()).acknowledgeForReservation(anyList());   // D16 × D18 — ② 건너뜀
        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.SUCCEEDED);
        assertThat(lastSavedReservation().getStatus()).isEqualTo(ReservedShipmentStatus.DONE);
    }

    @Test
    void run_marksCancelledOnRetryWhenFullyCancelledBeforeAcknowledge() {
        startWith(reservation(1), item(ReservedItemProgress.CANCEL_CHECKED, ReservedItemResult.FAILED));
        linesAre(line(OrderStatus.PAID, 1, 1));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        verify(orderAcknowledgeService, never()).acknowledgeForReservation(anyList());
        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.CANCELLED);    // FAILED 아님
        verify(orderShipmentRepository).clearInternalStage(List.of(10L));
        assertThat(lastSavedReservation().getStatus()).isEqualTo(ReservedShipmentStatus.DONE);
    }

    @Test
    void run_marksExternalOnRetryWhenShippedBeforeInvoice() {
        startWith(reservation(1), item(ReservedItemProgress.ACKNOWLEDGED, ReservedItemResult.FAILED));
        linesAre(line(OrderStatus.SHIPPED, 1, 0));                                // WING 에서 발송됨

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        verify(shipmentConfirmService, never()).sendReservedInvoices(anyList());   // D16 × D18 — ③ 요청에서 뺀다
        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.EXTERNAL);
        assertThat(lastSavedItem().getFailureReason()).isNull();
        verify(orderShipmentRepository).clearInternalStage(List.of(10L));
        assertThat(lastSavedReservation().getStatus()).isEqualTo(ReservedShipmentStatus.DONE);
        assertThat(lastSavedReservation().getRetryCount()).isEqualTo(1);           // 실패 실행으로 세지 않는다
    }

    @Test
    void run_releasesOnRetryWhenPartiallyCancelledBeforeAcknowledge() {
        startWith(reservation(1), item(ReservedItemProgress.CANCEL_CHECKED, ReservedItemResult.FAILED));
        linesAre(line(OrderStatus.PAID, 2, 1));                                   // ① 뒤에 고객이 1개 취소

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        verify(orderAcknowledgeService, never()).acknowledgeForReservation(anyList());   // D16 🔁 — ② 요청에서 뺀다
        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.RELEASED);
        assertThat(lastSavedItem().getFailureReason()).isEqualTo(ReservedShipmentExecutor.PARTIAL_CANCEL_MESSAGE);
        verify(orderShipmentRepository).setInternalStage(List.of(10L), "INTERNAL_PREPARING");
        assertThat(lastSavedReservation().getRetryCount()).isEqualTo(1);           // 실패 실행으로 세지 않는다
    }

    @Test
    void run_releasesOnRetryWhenPartiallyCancelledBeforeInvoice() {
        startWith(reservation(1), item(ReservedItemProgress.ACKNOWLEDGED, ReservedItemResult.FAILED));
        linesAre(line(OrderStatus.PREPARING, 2, 1));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        verify(shipmentConfirmService, never()).sendReservedInvoices(anyList());   // D16 🔁 — ③ 요청에서 뺀다
        assertThat(lastSavedItem().getResult()).isEqualTo(ReservedItemResult.RELEASED);
        verify(orderShipmentRepository).setInternalStage(List.of(10L), "INTERNAL_PREPARING");
        verify(orderShipmentRepository, never()).clearInternalStage(anyCollection());
    }

    @Test
    void run_writesLastRunAtOnlyOnRowsItRan() {
        startWith(reservation(1), item(ReservedItemProgress.ACKNOWLEDGED, ReservedItemResult.FAILED));
        ReservedShipmentItem finished = item(ReservedItemProgress.INVOICED, ReservedItemResult.SUCCEEDED).toBuilder()
                .id(101L).lastRunAt(LocalDateTime.of(2026, 9, 29, 0, 2)).build();
        given(reservedShipmentItemRepository.findByReservedShipment_Id(9L)).willReturn(List.of(finished));
        linesAre(line(OrderStatus.PREPARING, 1, 0));
        given(shipmentConfirmService.sendReservedInvoices(anyList()))
                .willReturn(new ReservedInvoiceResult(List.of(10L), Map.of()));

        executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME);

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ReservedShipmentItem::getId).containsOnly(100L);  // D28 — 끝난 행은 다시 쓰지 않는다
        assertThat(lastSavedItem().getLastRunAt()).isEqualTo(lastSavedReservation().getLastRunAt());   // 그 행의 시각 = 이번 실행
    }

    @Test
    void run_returnsEmptyWhenAlreadyClaimed() {
        given(reservedShipmentRepository.transition(9L, ReservedShipmentStatus.SCHEDULED, ReservedShipmentStatus.RUNNING))
                .willReturn(0);

        assertThat(executor.run(9L, ReservedShipmentStatus.SCHEDULED, ReservedRunKind.ON_TIME)).isEmpty();
        verify(reservedShipmentItemRepository, never()).findByReservedShipment_IdAndResultIn(any(), anyCollection());
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private void startWith(ReservedShipment reservation, ReservedShipmentItem item) {
        given(reservedShipmentRepository.transition(eq(9L), any(), eq(ReservedShipmentStatus.RUNNING))).willReturn(1);
        given(reservedShipmentRepository.findScopedById(9L)).willReturn(Optional.of(reservation));
        given(reservedShipmentItemRepository.findByReservedShipment_IdAndResultIn(eq(9L), anyCollection()))
                .willReturn(List.of(item));
    }

    private void refreshOk() {
        given(orderRefreshService.refresh(any()))
                .willReturn(new OrderRefreshResult(1, 1, List.of(), List.of(), List.of(), List.of()));
    }

    private void linesAre(OrderLine line) {
        given(orderLineRepository.findWithAccountByOrderShipment_IdIn(anyCollection())).willReturn(List.of(line));
    }

    private ReservedShipment reservation(int retryCount) {
        LocalDateTime at = LocalDateTime.of(2026, 9, 29, 0, 2);
        return ReservedShipment.builder().id(9L).executeAt(at).nextRunAt(at)
                .status(ReservedShipmentStatus.RUNNING).retryCount(retryCount).build();
    }

    private ReservedShipmentItem item(ReservedItemProgress progress, ReservedItemResult result) {
        return ReservedShipmentItem.builder().id(100L).orderShipment(shipment).externalOrderId("O1")
                .carrierCode("CJGLS").invoiceNumbers("111").progress(progress).result(result).build();
    }

    private OrderLine line(OrderStatus status, int orderQty, int cancelQty) {
        MarketplaceAccount account = MarketplaceAccount.builder().id(7L).platform(Platform.COUPANG).build();
        Order order = Order.builder().id(1L).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("O1").build();
        return OrderLine.builder().id(1L).order(order).orderShipment(shipment).status(status)
                .orderQty(orderQty).cancelQty(cancelQty).holdQty(0).build();
    }

    private ReservedShipmentItem lastSavedItem() {
        ArgumentCaptor<ReservedShipmentItem> captor = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private ReservedShipment lastSavedReservation() {
        ArgumentCaptor<ReservedShipment> captor = ArgumentCaptor.forClass(ReservedShipment.class);
        verify(reservedShipmentRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
