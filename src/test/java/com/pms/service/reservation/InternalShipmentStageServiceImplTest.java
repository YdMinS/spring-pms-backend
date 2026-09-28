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
import com.pms.domain.ReservedShipment;
import com.pms.domain.ReservedShipmentItem;
import com.pms.domain.ReservedShipmentStatus;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ReservedShipmentItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InternalShipmentStageServiceImplTest {

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private OrderShipmentRepository orderShipmentRepository;
    @Mock private ReservedShipmentItemRepository reservedShipmentItemRepository;
    @Mock private ReservedShipmentSettler reservedShipmentSettler;
    @InjectMocks private InternalShipmentStageServiceImpl service;

    @Test
    void markInternal_setsStageOnPaidShipment() {
        given(orderLineRepository.findWithAccountByIdIn(anyList()))
                .willReturn(List.of(line(1L, "O1", shipment(10L, null), OrderStatus.PAID, 0)));

        InternalStageResult result = service.markInternal(List.of(1L));

        verify(orderShipmentRepository).setInternalStage(List.of(10L), "INTERNAL_PREPARING");
        assertThat(result.changedShipments()).isEqualTo(1);
        assertThat(result.skippedOrderIds()).isEmpty();
    }

    @Test
    void markInternal_skipsNonPaidAndAlreadyInternal() {
        given(orderLineRepository.findWithAccountByIdIn(anyList())).willReturn(List.of(
                line(1L, "O1", shipment(10L, null), OrderStatus.PREPARING, 0),
                line(2L, "O2", shipment(20L, InternalShipmentStage.INTERNAL_PREPARING), OrderStatus.PAID, 0),
                line(3L, "O3", shipment(30L, null), OrderStatus.PAID, 1)));   // 전량취소

        InternalStageResult result = service.markInternal(List.of(1L, 2L, 3L));

        verify(orderShipmentRepository, never()).setInternalStage(anyCollection(), any());
        assertThat(result.skippedOrderIds()).containsExactly("O1", "O2", "O3");
    }

    @Test
    void releaseInternal_clearsOnlyInternalPreparing() {
        given(orderLineRepository.findWithAccountByIdIn(anyList())).willReturn(List.of(
                line(1L, "O1", shipment(10L, InternalShipmentStage.INTERNAL_PREPARING), OrderStatus.PAID, 0),
                line(2L, "O2", shipment(20L, InternalShipmentStage.AWAITING_SHIPMENT), OrderStatus.PAID, 0)));

        InternalStageResult result = service.releaseInternal(List.of(1L, 2L));

        verify(orderShipmentRepository).clearInternalStage(List.of(10L));
        assertThat(result.skippedOrderIds()).containsExactly("O2");
    }

    @Test
    void assertNotRunning_throwsWhenRunningReservationExists() {
        given(reservedShipmentItemRepository.existsByOrderShipment_IdInAndReservedShipment_Status(
                Set.of(10L), ReservedShipmentStatus.RUNNING)).willReturn(true);

        assertThatThrownBy(() -> service.assertNotRunning(Set.of(10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(InternalShipmentStageServiceImpl.RUNNING_MESSAGE);
    }

    @Test
    void clearAfterManualAcknowledge_releasesOpenItemsAndClearsStage() {
        ReservedShipment reservation = ReservedShipment.builder().id(9L).status(ReservedShipmentStatus.SCHEDULED).build();
        ReservedShipmentItem item = ReservedShipmentItem.builder().id(100L).reservedShipment(reservation)
                .orderShipment(shipment(10L, InternalShipmentStage.AWAITING_SHIPMENT)).externalOrderId("O1")
                .carrierCode("CJGLS").invoiceNumbers("111").progress(ReservedItemProgress.NONE)
                .result(ReservedItemResult.PENDING).build();
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(item));

        service.clearAfterManualAcknowledge(Set.of(10L));

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        assertThat(saved.getValue().getResult()).isEqualTo(ReservedItemResult.RELEASED);
        verify(orderShipmentRepository).clearInternalStage(Set.of(10L));
        verify(reservedShipmentSettler).settle(Set.of(9L));
    }

    @Test
    void clearAfterShipNow_releasesStoredInvoiceWithoutReservation() {
        ReservedShipmentItem stored = ReservedShipmentItem.builder().id(101L)
                .orderShipment(shipment(10L, InternalShipmentStage.INTERNAL_PREPARING)).externalOrderId("O1")
                .carrierCode("CJGLS").invoiceNumbers("111").progress(ReservedItemProgress.NONE)
                .result(ReservedItemResult.STORED).build();
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(stored));

        service.clearAfterShipNow(Set.of(10L));

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        assertThat(saved.getValue().getResult()).isEqualTo(ReservedItemResult.RELEASED);   // D18 보관 송장도 해제
        verify(orderShipmentRepository).clearInternalStage(Set.of(10L));
        verify(reservedShipmentSettler).settle(Set.of());                                  // 예약 없음 — 닫을 예약 0건
    }

    @Test
    void releasePartialCancel_releasesOpenItemsAndReturnsToInternalPreparing() {
        ReservedShipment reservation = ReservedShipment.builder().id(9L).status(ReservedShipmentStatus.SCHEDULED).build();
        ReservedShipmentItem item = ReservedShipmentItem.builder().id(100L).reservedShipment(reservation)
                .orderShipment(shipment(10L, InternalShipmentStage.AWAITING_SHIPMENT)).externalOrderId("O1")
                .carrierCode("CJGLS").invoiceNumbers("111").progress(ReservedItemProgress.NONE)
                .result(ReservedItemResult.PENDING).build();
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(item));

        service.releasePartialCancel(List.of(10L));

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        assertThat(saved.getValue().getResult()).isEqualTo(ReservedItemResult.RELEASED);
        assertThat(saved.getValue().getFailureReason()).isEqualTo(ReservedShipmentExecutor.PARTIAL_CANCEL_MESSAGE);
        verify(orderShipmentRepository).setInternalStage(List.of(10L), "INTERNAL_PREPARING");   // D17 — 「내부 상품준비중」 복귀
        verify(orderShipmentRepository, never()).clearInternalStage(anyCollection());
        verify(reservedShipmentSettler).settle(Set.of(9L));
    }

    @Test
    void releasePartialCancel_alsoDiscardsStoredInvoice() {
        ReservedShipmentItem stored = ReservedShipmentItem.builder().id(102L)
                .orderShipment(shipment(10L, InternalShipmentStage.INTERNAL_PREPARING)).externalOrderId("O1")
                .carrierCode("CJGLS").invoiceNumbers("111").progress(ReservedItemProgress.NONE)
                .result(ReservedItemResult.STORED).build();
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(stored));

        service.releasePartialCancel(List.of(10L));

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        assertThat(saved.getValue().getResult()).isEqualTo(ReservedItemResult.RELEASED);   // D18 — 일부 수량 취소는 송장을 버린다
        verify(reservedShipmentSettler).settle(Set.of());                                   // STORED 는 예약이 없다
        verify(reservedShipmentItemRepository).findByOrderShipment_IdInAndResultIn(List.of(10L),
                List.of(ReservedItemResult.PENDING, ReservedItemResult.FAILED, ReservedItemResult.STORED));
    }

    @Test
    void releaseInternal_releasesStoredInvoice() {
        OrderShipment internal = shipment(10L, InternalShipmentStage.INTERNAL_PREPARING);
        given(orderLineRepository.findWithAccountByIdIn(anyList()))
                .willReturn(List.of(line(1L, "O1", internal, OrderStatus.PAID, 0)));
        ReservedShipmentItem stored = ReservedShipmentItem.builder().id(101L).orderShipment(internal).externalOrderId("O1")
                .carrierCode("CJGLS").invoiceNumbers("111").progress(ReservedItemProgress.NONE)
                .result(ReservedItemResult.STORED).build();
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(stored));

        service.releaseInternal(List.of(1L));

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        assertThat(saved.getValue().getResult()).isEqualTo(ReservedItemResult.RELEASED);   // D18 — 해제한 주문의 송장은 무효
        assertThat(saved.getValue().getFailureReason())
                .isEqualTo(InternalShipmentStageServiceImpl.RELEASED_BY_INTERNAL_RELEASE);
        verify(orderShipmentRepository).clearInternalStage(List.of(10L));
    }

    private OrderShipment shipment(Long id, InternalShipmentStage stage) {
        return OrderShipment.builder().id(id).externalShipmentId("B" + id).internalStage(stage).build();
    }

    private OrderLine line(Long id, String orderId, OrderShipment shipment, OrderStatus status, int cancelQty) {
        MarketplaceAccount account = MarketplaceAccount.builder().id(7L).platform(Platform.COUPANG).build();
        Order order = Order.builder().id(id + 100).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(orderId).build();
        return OrderLine.builder().id(id).order(order).orderShipment(shipment).status(status)
                .orderQty(1).cancelQty(cancelQty).holdQty(0).build();
    }
}
