package com.pms.service;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.service.elevenst.ElevenstSellerActions;
import com.pms.service.elevenst.ElevenstSellerActions.SplitInvoice;
import com.pms.service.stock.OrderLineExpander;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D64: the partial (1636) and split (6791) rules before 11st is called, and what is marked shipped. */
@ExtendWith(MockitoExtension.class)
class ElevenstManualShipperTest {

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ElevenstOrderLineRepository elevenstOrderLineRepository;
    @Mock private ElevenstSellerActions elevenstSellerActions;
    @Mock private ShipmentParcelRecorder shipmentParcelRecorder;
    @Mock private ShipmentParcelItemRepository shipmentParcelItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private OrderLineExpander orderLineExpander;
    @InjectMocks private ElevenstManualShipper shipper;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();
    private final Order order = Order.builder().marketplaceAccount(account).platform(account.getPlatform())
            .externalOrderId("O1").build();
    private final OrderShipment shipment = OrderShipment.builder().id(3L).order(order).externalShipmentId("D1").build();

    private OrderLine line(long id, int qty) {
        return OrderLine.builder().id(id).order(order).orderShipment(shipment).orderQty(qty).cancelQty(0).holdQty(0)
                .status(OrderStatus.PREPARING).build();
    }

    private ElevenstOrderLine mirror(OrderLine line, String seq, String addPrdYn) {
        return ElevenstOrderLine.builder().orderLine(line).ordNo("O1").ordPrdSeq(seq)
                .raw("<order><addPrdYn>" + addPrdYn + "</addPrdYn></order>").build();
    }

    @Test
    void partial_addOnLinesOnly_is400_beforeCalling11st() {
        OrderLine main = line(10L, 1);
        OrderLine addOn = line(11L, 1);
        given(orderLineRepository.findByExternalOrderId("O1")).willReturn(List.of(main, addOn));
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(List.of(11L))).willReturn(List.of(mirror(addOn, "2", "Y")));

        assertThatThrownBy(() -> shipper.ship(new ManualShipmentRequest(10L, "00034", "612345678901",
                ManualShipmentRequest.MODE_PARTIAL, List.of(11L), null), main, account, "00034", "612345678901"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("추가구성 상품만 따로 보낼 수 없습니다.");
        verify(elevenstSellerActions, never()).shipPartial(any(), any(), any(), anyList(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void partial_sendsOnlyTheChosenLines_andMarksOnlyThemShipped() {
        OrderLine first = line(10L, 1);
        OrderLine second = line(11L, 1);
        given(orderLineRepository.findByExternalOrderId("O1")).willReturn(List.of(first, second));
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(List.of(11L))).willReturn(List.of(mirror(second, "2", "N")));

        ManualShipmentResult result = shipper.ship(new ManualShipmentRequest(10L, "00034", "612345678901",
                ManualShipmentRequest.MODE_PARTIAL, List.of(11L), null), first, account, "00034", "612345678901");

        verify(elevenstSellerActions).shipPartial(eq(account), eq("D1"), eq("O1"), eq(List.of("2")), eq("00034"),
                eq("612345678901"), any());
        ArgumentCaptor<List<OrderLine>> saved = ArgumentCaptor.forClass(List.class);
        verify(orderLineRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(OrderLine::getId).containsExactly(11L);
        assertThat(result.resultStatus()).isNull();   // the anchor line (10) was not sent
    }

    @Test
    void split_quantitiesMustAddUpToTheOrderQuantity() {
        OrderLine line = line(10L, 3);
        given(orderLineRepository.findByExternalOrderId("O1")).willReturn(List.of(line));
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(List.of(10L))).willReturn(List.of(mirror(line, "1", "N")));

        assertThatThrownBy(() -> shipper.ship(new ManualShipmentRequest(10L, "00034", "111111111111",
                ManualShipmentRequest.MODE_SPLIT, List.of(10L), List.of(
                        new ManualShipmentRequest.Split("111111111111", 1),
                        new ManualShipmentRequest.Split("222222222222", 1))), line, account, "00034", "111111111111"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("송장별 수량의 합이 주문 수량(3개)과 같아야 합니다.");
    }

    @Test
    void split_sendsEveryInvoiceWithItsQuantity() {
        OrderLine line = line(10L, 3);
        given(orderLineRepository.findByExternalOrderId("O1")).willReturn(List.of(line));
        given(elevenstOrderLineRepository.findByOrderLine_IdIn(List.of(10L))).willReturn(List.of(mirror(line, "1", "N")));

        ManualShipmentResult result = shipper.ship(new ManualShipmentRequest(10L, "00034", "111111111111",
                ManualShipmentRequest.MODE_SPLIT, List.of(10L), List.of(
                        new ManualShipmentRequest.Split("1111-1111-1111", 1),
                        new ManualShipmentRequest.Split("222222222222", 2))), line, account, "00034", "111111111111");

        verify(elevenstSellerActions).shipSplit(eq(account), eq("D1"), eq("O1"), eq("1"), eq("00034"),
                eq(List.of(new SplitInvoice("111111111111", 1), new SplitInvoice("222222222222", 2))), any());
        assertThat(result.resultStatus()).isEqualTo("SHIPPED");
    }
}
