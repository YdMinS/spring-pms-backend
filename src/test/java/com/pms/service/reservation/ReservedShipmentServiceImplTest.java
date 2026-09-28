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
import com.pms.dto.response.ReservationCreateResult;
import com.pms.dto.response.ReservedShipmentRowResponse;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ReservedShipmentItemRepository;
import com.pms.repository.ReservedShipmentRepository;
import com.pms.service.CarrierCodeService;
import com.pms.service.ReservedInvoice;
import com.pms.service.ShipmentConfirmResult;
import com.pms.service.ShipmentConfirmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservedShipmentServiceImplTest {

    @Mock private ShipmentConfirmService shipmentConfirmService;
    @Mock private CarrierCodeService carrierCodeService;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private OrderShipmentRepository orderShipmentRepository;
    @Mock private ReservedShipmentRepository reservedShipmentRepository;
    @Mock private ReservedShipmentItemRepository reservedShipmentItemRepository;
    @Mock private ReservedShipmentSettler reservedShipmentSettler;
    @Mock private InternalShipmentStageService internalShipmentStageService;
    @Mock private ReservedShipmentExecutor reservedShipmentExecutor;
    @InjectMocks private ReservedShipmentServiceImpl service;

    private final MockMultipartFile file = new MockMultipartFile("file", "r.xlsx", "application/octet-stream", new byte[]{1});
    private final LocalDateTime future = LocalDateTime.now().plusDays(2);

    @Test
    void create_rejectsPastTime() {
        assertThatThrownBy(() -> service.create(file, LocalDateTime.of(2020, 1, 1, 0, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ReservedShipmentServiceImpl.PAST_TIME_MESSAGE);
        verify(shipmentConfirmService, never()).readInvoicesByOrderId(any());
    }

    @Test
    void create_excludesOrderWithoutInternalShipment() {
        fileHas("O1", List.of("111"));
        given(orderLineRepository.findByExternalOrderId("O1")).willReturn(List.of(line(shipment(10L, null))));

        ReservationCreateResult result = service.create(file, future);

        assertThat(result.reservationId()).isNull();
        assertThat(result.excluded()).extracting(ReservationCreateResult.ExcludedOrder::reason)
                .containsExactly("내부 상품준비중 주문이 아닙니다");
        verify(reservedShipmentRepository, never()).save(any());
    }

    @Test
    void create_excludesInternalShipmentWhenNotPaid() {
        fileHas("O1", List.of("111"));
        given(orderLineRepository.findByExternalOrderId("O1")).willReturn(List.of(
                line(shipment(10L, InternalShipmentStage.INTERNAL_PREPARING), OrderStatus.PREPARING)));

        ReservationCreateResult result = service.create(file, future);

        assertThat(result.excluded()).extracting(ReservationCreateResult.ExcludedOrder::reason)
                .containsExactly("내부 상품준비중 주문이 아닙니다");      // D29 — WING 에서 이미 발주처리된 주문
        verify(reservedShipmentRepository, never()).save(any());
    }

    @Test
    void create_reservesInternalShipmentWithoutParcel() {
        fileHas("O1", List.of("111", "222"));
        given(orderLineRepository.findByExternalOrderId("O1"))
                .willReturn(List.of(line(shipment(10L, InternalShipmentStage.INTERNAL_PREPARING))));
        given(reservedShipmentRepository.save(any())).willReturn(ReservedShipment.builder().id(9L)
                .executeAt(future).nextRunAt(future).status(ReservedShipmentStatus.SCHEDULED).build());

        ReservationCreateResult result = service.create(file, future);

        ArgumentCaptor<ReservedShipmentItem> item = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(item.capture());
        assertThat(item.getValue().getInvoiceNumbers()).isEqualTo("111,222");
        assertThat(item.getValue().getCarrierCode()).isEqualTo("CJGLS");
        assertThat(item.getValue().getResult()).isEqualTo(ReservedItemResult.PENDING);
        verify(orderShipmentRepository).setInternalStage(List.of(10L), "AWAITING_SHIPMENT");
        assertThat(result.reservationId()).isEqualTo(9L);
        assertThat(result.reservedShipments()).isEqualTo(1);
    }

    @Test
    void create_replacesInvoiceOfAwaitingShipment() {
        fileHas("O1", List.of("333"));
        given(orderLineRepository.findByExternalOrderId("O1"))
                .willReturn(List.of(line(shipment(10L, InternalShipmentStage.AWAITING_SHIPMENT))));
        ReservedShipment scheduled = ReservedShipment.builder().id(9L).status(ReservedShipmentStatus.SCHEDULED).build();
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(ReservedShipmentItem.builder().id(100L).reservedShipment(scheduled)
                        .orderShipment(shipment(10L, InternalShipmentStage.AWAITING_SHIPMENT)).externalOrderId("O1")
                        .carrierCode("CJGLS").invoiceNumbers("111").progress(ReservedItemProgress.NONE)
                        .result(ReservedItemResult.PENDING).build()));

        ReservationCreateResult result = service.create(file, future);

        ArgumentCaptor<ReservedShipmentItem> item = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(item.capture());
        assertThat(item.getValue().getInvoiceNumbers()).isEqualTo("333");
        assertThat(result.updatedInvoices()).isEqualTo(1);
    }

    @Test
    void list_usesEachRowsOwnLastRunAt() {
        LocalDateTime old = LocalDateTime.now().minusDays(30);
        LocalDateTime rowRun = LocalDateTime.now().minusHours(2);
        ReservedShipment reservation = ReservedShipment.builder().id(9L).executeAt(old).nextRunAt(old)
                .status(ReservedShipmentStatus.DONE).retryCount(1).lastRunAt(LocalDateTime.now().minusHours(1)).build();
        ReservedShipmentItem oldRow = item(100L, reservation).toBuilder()
                .result(ReservedItemResult.SUCCEEDED).lastRunAt(old).build();
        ReservedShipmentItem newRow = item(101L, reservation).toBuilder()
                .result(ReservedItemResult.SUCCEEDED).lastRunAt(rowRun).build();
        given(reservedShipmentRepository.findForList(anyCollection(), any())).willReturn(List.of(reservation));
        given(reservedShipmentItemRepository.findByReservedShipment_IdIn(anyCollection())).willReturn(List.of(oldRow, newRow));

        List<ReservedShipmentRowResponse> rows = service.list();

        assertThat(rows).extracting(ReservedShipmentRowResponse::id).containsExactly(101L);   // D30 — 끝난 행은 그 행의 시각으로 7일
        assertThat(rows.get(0).lastRunAt()).isEqualTo(rowRun);                                 // D28 — 예약이 아니라 그 행의 시각
    }

    @Test
    void changeTime_rejectsStartedReservation() {
        ReservedShipment started = ReservedShipment.builder().id(9L)
                .status(ReservedShipmentStatus.SCHEDULED).firstRunKind(ReservedRunKind.ON_TIME).build();
        given(reservedShipmentItemRepository.findScopedById(100L)).willReturn(Optional.of(item(100L, started)));

        assertThatThrownBy(() -> service.changeTime(100L, future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ReservedShipmentServiceImpl.STARTED_MESSAGE);
    }

    @Test
    void changeTime_detachesOrderFromSharedReservation() {
        ReservedShipment shared = ReservedShipment.builder().id(9L).executeAt(future).nextRunAt(future)
                .status(ReservedShipmentStatus.SCHEDULED).retryCount(0).build();
        ReservedShipmentItem mine = item(100L, shared);
        given(reservedShipmentItemRepository.findScopedById(100L)).willReturn(Optional.of(mine));
        given(reservedShipmentItemRepository.findByReservedShipment_Id(9L)).willReturn(List.of(mine, item(101L, shared)));
        given(reservedShipmentRepository.save(any())).willReturn(shared.toBuilder().id(10L).build());
        LocalDateTime later = future.plusHours(3);

        service.changeTime(100L, later);

        ArgumentCaptor<ReservedShipment> saved = ArgumentCaptor.forClass(ReservedShipment.class);
        verify(reservedShipmentRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getId()).isNull();             // D30 — 이 주문만 새 예약으로 떼어낸다
        assertThat(saved.getAllValues().get(1).getId()).isEqualTo(10L);
        assertThat(saved.getAllValues().get(1).getExecuteAt()).isEqualTo(later);
        ArgumentCaptor<ReservedShipmentItem> moved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(moved.capture());
        assertThat(moved.getValue().getReservedShipment().getId()).isEqualTo(10L);
    }

    @Test
    void changeTime_detachesEvenWhenSiblingIsReleased() {
        ReservedShipment shared = ReservedShipment.builder().id(9L).executeAt(future).nextRunAt(future)
                .status(ReservedShipmentStatus.SCHEDULED).retryCount(0).build();
        ReservedShipmentItem mine = item(100L, shared);
        ReservedShipmentItem released = item(101L, shared).toBuilder().result(ReservedItemResult.RELEASED).build();
        given(reservedShipmentItemRepository.findScopedById(100L)).willReturn(Optional.of(mine));
        given(reservedShipmentItemRepository.findByReservedShipment_Id(9L)).willReturn(List.of(mine, released));
        given(reservedShipmentRepository.save(any())).willReturn(shared.toBuilder().id(10L).build());

        service.changeTime(100L, future.plusHours(3));

        verify(reservedShipmentRepository, times(2)).save(any());   // D30 — 닫힌 형제 행만 있어도 떼어낸다(형제의 예정 시각 불변)
        verify(reservedShipmentSettler).settle(List.of(9L));        // 원래 예약은 남은 행이 전부 끝났으면 닫힌다
    }

    @Test
    void changeInvoice_updatesStoredInvoiceOfInternalShipment() {
        given(carrierCodeService.validateDeliveryCompanyCode("HANJIN", Platform.COUPANG)).willReturn("HANJIN");
        OrderShipment internal = shipment(10L, InternalShipmentStage.INTERNAL_PREPARING);
        given(orderLineRepository.findWithAccountByOrderShipment_IdIn(anyCollection())).willReturn(List.of(line(internal)));
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(stored(100L, internal)));

        service.changeInvoice(10L, "HANJIN", "555-666");

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(100L);                    // 같은 행을 고친다
        assertThat(saved.getValue().getCarrierCode()).isEqualTo("HANJIN");
        assertThat(saved.getValue().getInvoiceNumbers()).isEqualTo("555666");
        assertThat(saved.getValue().getResult()).isEqualTo(ReservedItemResult.STORED);
    }

    @Test
    void changeInvoice_storesNewInvoiceWhenNone() {
        given(carrierCodeService.validateDeliveryCompanyCode("CJGLS", Platform.COUPANG)).willReturn("CJGLS");
        OrderShipment internal = shipment(10L, InternalShipmentStage.INTERNAL_PREPARING);
        given(orderLineRepository.findWithAccountByOrderShipment_IdIn(anyCollection())).willReturn(List.of(line(internal)));

        service.changeInvoice(10L, "CJGLS", "777");

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getReservedShipment()).isNull();              // D18 — 예약 없이 보관
        assertThat(saved.getValue().getResult()).isEqualTo(ReservedItemResult.STORED);
        assertThat(saved.getValue().getExternalOrderId()).isEqualTo("O1");
    }

    @Test
    void changeInvoice_rejectsRunningReservation() {
        given(carrierCodeService.validateDeliveryCompanyCode("HANJIN", Platform.COUPANG)).willReturn("HANJIN");
        given(orderLineRepository.findWithAccountByOrderShipment_IdIn(anyCollection()))
                .willReturn(List.of(line(shipment(10L, InternalShipmentStage.AWAITING_SHIPMENT))));
        willThrow(new IllegalArgumentException(InternalShipmentStageServiceImpl.RUNNING_MESSAGE))
                .given(internalShipmentStageService).assertNotRunning(anyCollection());

        assertThatThrownBy(() -> service.changeInvoice(10L, "HANJIN", "555666"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(InternalShipmentStageServiceImpl.RUNNING_MESSAGE);
        verify(reservedShipmentItemRepository, never()).save(any());
    }

    @Test
    void changeInvoice_updatesFailedItemAfterAcknowledge() {
        given(carrierCodeService.validateDeliveryCompanyCode("HANJIN", Platform.COUPANG)).willReturn("HANJIN");
        OrderShipment awaiting = shipment(10L, InternalShipmentStage.AWAITING_SHIPMENT);
        given(orderLineRepository.findWithAccountByOrderShipment_IdIn(anyCollection()))
                .willReturn(List.of(line(awaiting, OrderStatus.PREPARING)));        // ② 발주처리 성공 — 저장 status PREPARING
        ReservedShipment retrying = ReservedShipment.builder().id(9L).status(ReservedShipmentStatus.SCHEDULED)
                .retryCount(1).build();
        ReservedShipmentItem failed = ReservedShipmentItem.builder().id(100L).reservedShipment(retrying)
                .orderShipment(awaiting).externalOrderId("O1").carrierCode("CJGLS").invoiceNumbers("111")
                .progress(ReservedItemProgress.ACKNOWLEDGED).result(ReservedItemResult.FAILED).build();
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(failed));

        service.changeInvoice(10L, "HANJIN", "555-666");

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        ReservedShipmentItem next = saved.getValue();
        assertThat(next.getId()).isEqualTo(100L);                                   // D18 · D29 예외 — 같은 결과 행을 고친다
        assertThat(next.getCarrierCode()).isEqualTo("HANJIN");
        assertThat(next.getInvoiceNumbers()).isEqualTo("555666");
        assertThat(next.getReservedShipment().getId()).isEqualTo(9L);               // 같은 예약 — 다음 재시도가 이 행을 다시 읽어 새 송장으로 ③
        assertThat(next.getResult()).isEqualTo(ReservedItemResult.FAILED);
        assertThat(next.getProgress()).isEqualTo(ReservedItemProgress.ACKNOWLEDGED); // D16 — 실패한 단계(③)부터
    }

    @Test
    void cancelItems_keepsInvoiceAsStored() {
        OrderShipment awaiting = shipment(10L, InternalShipmentStage.AWAITING_SHIPMENT);
        given(orderLineRepository.findWithAccountByIdIn(anyList())).willReturn(List.of(line(awaiting)));
        ReservedShipment scheduled = ReservedShipment.builder().id(9L).status(ReservedShipmentStatus.SCHEDULED).build();
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(ReservedShipmentItem.builder().id(100L).reservedShipment(scheduled)
                        .orderShipment(awaiting).externalOrderId("O1").carrierCode("CJGLS").invoiceNumbers("111")
                        .progress(ReservedItemProgress.NONE).result(ReservedItemResult.PENDING).build()));

        service.cancelItems(List.of(1L));

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getResult()).isEqualTo(ReservedItemResult.RELEASED);
        ReservedShipmentItem kept = saved.getAllValues().get(1);                   // D18 🔁 — 송장은 주문에 남는다
        assertThat(kept.getId()).isNull();
        assertThat(kept.getReservedShipment()).isNull();
        assertThat(kept.getResult()).isEqualTo(ReservedItemResult.STORED);
        assertThat(kept.getInvoiceNumbers()).isEqualTo("111");
        verify(orderShipmentRepository).setInternalStage(Set.of(10L), "INTERNAL_PREPARING");
    }

    @Test
    void reserveStored_attachesStoredInvoiceToNewReservation() {
        OrderShipment internal = shipment(10L, InternalShipmentStage.INTERNAL_PREPARING);
        given(orderLineRepository.findWithAccountByIdIn(anyList())).willReturn(List.of(line(internal)));
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(stored(100L, internal)));
        given(reservedShipmentRepository.save(any())).willReturn(ReservedShipment.builder().id(9L)
                .executeAt(future).nextRunAt(future).status(ReservedShipmentStatus.SCHEDULED).build());

        ReservationCreateResult result = service.reserveStored(List.of(1L), future);

        ArgumentCaptor<ReservedShipmentItem> saved = ArgumentCaptor.forClass(ReservedShipmentItem.class);
        verify(reservedShipmentItemRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(100L);                    // 보관 행을 예약으로 옮긴다
        assertThat(saved.getValue().getReservedShipment().getId()).isEqualTo(9L);
        assertThat(saved.getValue().getResult()).isEqualTo(ReservedItemResult.PENDING);
        assertThat(saved.getValue().getInvoiceNumbers()).isEqualTo("111");
        verify(orderShipmentRepository).setInternalStage(List.of(10L), "AWAITING_SHIPMENT");
        assertThat(result.reservedShipments()).isEqualTo(1);
    }

    @Test
    void reserveStored_excludesOrderWithoutStoredInvoice() {
        given(orderLineRepository.findWithAccountByIdIn(anyList()))
                .willReturn(List.of(line(shipment(10L, InternalShipmentStage.INTERNAL_PREPARING))));

        ReservationCreateResult result = service.reserveStored(List.of(1L), future);

        assertThat(result.excluded()).extracting(ReservationCreateResult.ExcludedOrder::reason)
                .containsExactly(ReservedShipmentServiceImpl.NO_STORED_INVOICE_MESSAGE);
        verify(reservedShipmentRepository, never()).save(any());
    }

    @Test
    void shipStoredNow_sendsStoredInvoiceAndReportsOrderWithout() {
        OrderShipment withInvoice = shipment(10L, InternalShipmentStage.INTERNAL_PREPARING);
        OrderShipment without = shipment(20L, InternalShipmentStage.INTERNAL_PREPARING);
        given(orderLineRepository.findWithAccountByIdIn(anyList()))
                .willReturn(List.of(line(withInvoice), lineOf(2L, "O2", without)));
        given(reservedShipmentItemRepository.findByOrderShipment_IdInAndResultIn(anyCollection(), anyCollection()))
                .willReturn(List.of(stored(100L, withInvoice)));
        given(shipmentConfirmService.shipStoredInvoices(anyList()))
                .willReturn(new ShipmentConfirmResult(1, 1, List.of(), 1, List.of(), List.of()));

        ShipmentConfirmResult result = service.shipStoredNow(List.of(1L, 2L));

        verify(shipmentConfirmService).shipStoredInvoices(
                List.of(new ReservedInvoice(10L, "O1", "HANJIN", List.of("111"))));
        assertThat(result.unmatched()).containsExactly("O2");                   // 저장된 송장 없음
        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(1);
    }

    private void fileHas(String orderId, List<String> invoices) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        parsed.put(orderId, invoices);
        given(shipmentConfirmService.readInvoicesByOrderId(any())).willReturn(parsed);
        given(carrierCodeService.resolveDeliveryCompanyCode(Platform.COUPANG)).willReturn("CJGLS");
    }

    private OrderShipment shipment(Long id, InternalShipmentStage stage) {
        return OrderShipment.builder().id(id).externalShipmentId("B" + id).internalStage(stage).build();
    }

    private OrderLine line(OrderShipment shipment) {
        return line(shipment, OrderStatus.PAID);
    }

    private OrderLine line(OrderShipment shipment, OrderStatus status) {
        MarketplaceAccount account = MarketplaceAccount.builder().id(7L).platform(Platform.COUPANG).build();
        Order order = Order.builder().id(1L).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("O1").build();
        return OrderLine.builder().id(1L).order(order).orderShipment(shipment).status(status)
                .orderQty(1).cancelQty(0).holdQty(0).build();
    }

    private OrderLine lineOf(Long id, String orderId, OrderShipment shipment) {
        MarketplaceAccount account = MarketplaceAccount.builder().id(7L).platform(Platform.COUPANG).build();
        Order order = Order.builder().id(id).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(orderId).build();
        return OrderLine.builder().id(id).order(order).orderShipment(shipment).status(OrderStatus.PAID)
                .orderQty(1).cancelQty(0).holdQty(0).build();
    }

    /** 예약 없이 보관한 송장 행(D18). */
    private ReservedShipmentItem stored(Long id, OrderShipment shipment) {
        return ReservedShipmentItem.builder().id(id).orderShipment(shipment).externalOrderId("O1")
                .carrierCode("HANJIN").invoiceNumbers("111").progress(ReservedItemProgress.NONE)
                .result(ReservedItemResult.STORED).build();
    }

    private ReservedShipmentItem item(Long id, ReservedShipment reservation) {
        return ReservedShipmentItem.builder().id(id).reservedShipment(reservation)
                .orderShipment(shipment(id - 90L, InternalShipmentStage.AWAITING_SHIPMENT)).externalOrderId("O" + id)
                .carrierCode("CJGLS").invoiceNumbers("111").progress(ReservedItemProgress.NONE)
                .result(ReservedItemResult.PENDING).build();
    }
}
