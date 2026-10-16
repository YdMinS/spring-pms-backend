package com.pms.service.elevenst;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.dto.request.ElevenstDeliveryDelayRequest;
import com.pms.dto.request.ElevenstVisitPickupRequest;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.service.coupang.SyncWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D63 · D66 · D67: checks before 11st is called, and the local write after a success. */
@ExtendWith(MockitoExtension.class)
class ElevenstOrderSellerServiceImplTest {

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ElevenstOrderLineRepository elevenstOrderLineRepository;
    @Mock private ElevenstSellerActions elevenstSellerActions;
    @InjectMocks private ElevenstOrderSellerServiceImpl service;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    private OrderLine line(long id, OrderStatus status) {
        Order order = Order.builder().marketplaceAccount(account).platform(account.getPlatform())
                .externalOrderId("O1").build();
        OrderShipment shipment = OrderShipment.builder().id(3L).order(order).externalShipmentId("D1").build();
        return OrderLine.builder().id(id).order(order).orderShipment(shipment).orderQty(1).cancelQty(0).holdQty(0)
                .status(status).build();
    }

    private void given11st(OrderLine line, String platformStatus, String raw) {
        given(orderLineRepository.findWithAccountAndSellerById(line.getId())).willReturn(Optional.of(line));
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(List.of(line.getId()))).willReturn(List.of(
                ElevenstOrderLine.builder().orderLine(line).ordNo("O1").ordPrdSeq("1").platformStatus(platformStatus)
                        .raw(raw).build()));
    }

    @Test
    void deliveryDelay_detailUnder20Bytes_is400_beforeCalling11st() {
        OrderLine paid = line(10L, OrderStatus.PAID);
        given11st(paid, "202", "<order/>");
        LocalDate tomorrow = LocalDate.now(SyncWindow.KST).plusDays(1);

        assertThatThrownBy(() -> service.deliveryDelay(10L,
                new ElevenstDeliveryDelayRequest(tomorrow, "01", "재고 부족")))   // 9 characters = 18 bytes
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상세 사유는 한글 10~150자로 적어 주세요.");
        verify(elevenstSellerActions, never()).deliveryDelay(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deliveryDelay_dateBeyond30Days_is400_andAValidOneIsSentPerDelivery() {
        OrderLine paid = line(10L, OrderStatus.PAID);
        given11st(paid, "202", "<order/>");
        LocalDate today = LocalDate.now(SyncWindow.KST);

        assertThatThrownBy(() -> service.deliveryDelay(10L,
                new ElevenstDeliveryDelayRequest(today.plusDays(31), "01", "재고가 곧 들어올 예정입니다")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ElevenstOrderSellerServiceImpl.DELAY_DATE_MESSAGE);

        service.deliveryDelay(10L, new ElevenstDeliveryDelayRequest(today.plusDays(30), "01", "재고가 곧 들어올 예정입니다"));

        verify(elevenstSellerActions).deliveryDelay(account, "O1", "D1", today.plusDays(30), "01", "재고가 곧 들어올 예정입니다");
    }

    @Test
    void visitPickup_notAVisitPickupLine_is400() {
        OrderLine preparing = line(10L, OrderStatus.PREPARING);
        given11st(preparing, "301", "<order><visitDlvYn>N</visitDlvYn></order>");

        assertThatThrownBy(() -> service.visitPickup(10L, new ElevenstVisitPickupRequest("A12")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ElevenstOrderSellerServiceImpl.VISIT_MESSAGE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void visitPickup_success_marksEveryLineOfTheDeliveryDelivered() {
        OrderLine first = line(10L, OrderStatus.PREPARING);
        OrderLine second = first.toBuilder().id(11L).build();
        given11st(first, "301", "<order><visitDlvYn>Y</visitDlvYn></order>");
        given(orderLineRepository.findByExternalOrderId("O1")).willReturn(List.of(first, second));

        service.visitPickup(10L, new ElevenstVisitPickupRequest(" A12 "));

        verify(elevenstSellerActions).visitPickup(account, "D1", "O1", "A12");
        ArgumentCaptor<List<OrderLine>> saved = ArgumentCaptor.forClass(List.class);
        verify(orderLineRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(OrderLine::getStatus)
                .containsExactly(OrderStatus.DELIVERED, OrderStatus.DELIVERED);
    }

    @Test
    void stockConfirm_reservedLine_sendsAndStoresTheSentTime_otherCodeIs400() {
        OrderLine reserved = line(10L, OrderStatus.PAID);
        given11st(reserved, "201", "<order/>");

        service.stockConfirm(10L);

        ArgumentCaptor<ElevenstOrderLine> saved = ArgumentCaptor.forClass(ElevenstOrderLine.class);
        verify(elevenstOrderLineRepository).save(saved.capture());
        assertThat(saved.getValue().getStockConfirmSentAt()).isNotNull();

        OrderLine paid = line(12L, OrderStatus.PAID);
        given11st(paid, "202", "<order/>");
        assertThatThrownBy(() -> service.stockConfirm(12L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ElevenstOrderSellerServiceImpl.STOCK_MESSAGE);
    }
}
