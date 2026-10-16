package com.pms.service.elevenst;

import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.ElevenstOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** FEATURE_2610_17 / D5 · D6 · D17 · D31 · D43: one 11st order row → order core + 11st mirror. */
@ExtendWith(MockitoExtension.class)
class ElevenstOrderUpserterTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderShipmentRepository orderShipmentRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ElevenstOrderLineRepository elevenstOrderLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @InjectMocks private ElevenstOrderUpserter upserter;

    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(5L).build();

    @BeforeEach
    void setUp() {
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderShipmentRepository.save(any(OrderShipment.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderLineRepository.save(any(OrderLine.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(elevenstOrderLineRepository.save(any(ElevenstOrderLine.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** A 1876 row as the guide lists its fields (§2-9 ② · §2-12 ①), personal fields included. */
    private Map<String, String> paidRow() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("ordNo", "20261015000001");
        row.put("ordPrdSeq", "1");
        row.put("dlvNo", "D777");
        row.put("prdNo", "P100");
        row.put("prdNm", "양말");
        row.put("slctPrdOptNm", "검정");
        row.put("prdStckNo", "STK-1");
        row.put("sellerStockCd", "70");
        row.put("ordQty", "2");
        row.put("selPrc", "5000");
        row.put("ordAmt", "10000");
        row.put("lstSellerDscPrc", "300");
        row.put("lstTmallDscPrc", "200");
        row.put("dlvCst", "3000");
        row.put("bmDlvCst", "0");
        row.put("ordStlEndDt", "2026-10-15 09:30:00");
        row.put("ordNm", "홍길동");
        row.put("rcvrNm", "김철수");
        row.put("rcvrBaseAddr", "서울시 어딘가");
        row.put("rcvrPrtblNo", "010-1234-5678");
        row.put("ordDlvReqCont", "문 앞");
        row.put("memID", "buyer01");
        return row;
    }

    @Test
    void newPaidRow_createsCoreAndMirror_withAmountsAndAllowListedRaw() {
        given(orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(5L, "20261015000001"))
                .willReturn(Optional.empty());
        given(orderShipmentRepository.findByOrder_IdAndExternalShipmentId(any(), any())).willReturn(Optional.empty());
        given(elevenstOrderLineRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(5L, "20261015000001", "1"))
                .willReturn(Optional.empty());
        ProductListingOption option = ProductListingOption.builder().id(70L).build();
        given(productListingOptionRepository.findElevenstOrderOption(70L, 5L, "P100")).willReturn(Optional.of(option));

        UpsertCount count = upserter.upsertRows(account, ElevenstOrderList.PAID, List.of(paidRow()));

        assertThat(count.newCount()).isEqualTo(1);
        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(order.capture());
        assertThat(order.getValue().getPlatform()).isEqualTo(Platform.ELEVENST);
        assertThat(order.getValue().getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 30));
        assertThat(order.getValue().getReceiverName()).isEqualTo("김철수");

        ArgumentCaptor<OrderShipment> shipment = ArgumentCaptor.forClass(OrderShipment.class);
        verify(orderShipmentRepository).save(shipment.capture());
        assertThat(shipment.getValue().getExternalShipmentId()).isEqualTo("D777");
        assertThat(shipment.getValue().getShippingFee()).isEqualByComparingTo("3000");

        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(line.getValue().getOrderQty()).isEqualTo(2);
        assertThat(line.getValue().getItemName()).isEqualTo("양말 / 검정");
        assertThat(line.getValue().getUnitPrice()).isEqualByComparingTo("5000");
        assertThat(line.getValue().getLineAmount()).isEqualByComparingTo("10000");
        assertThat(line.getValue().getDiscountAmount()).isEqualByComparingTo("500");
        assertThat(line.getValue().getPlatformDiscountAmount()).isEqualByComparingTo("200");
        assertThat(line.getValue().getProductListingOption()).isSameAs(option);

        ArgumentCaptor<ElevenstOrderLine> mirror = ArgumentCaptor.forClass(ElevenstOrderLine.class);
        verify(elevenstOrderLineRepository).save(mirror.capture());
        assertThat(mirror.getValue().getPlatformStatus()).isEqualTo("202");
        assertThat(mirror.getValue().getPrdStckNo()).isEqualTo("STK-1");
        assertThat(mirror.getValue().getRaw()).contains("<ordNo>20261015000001</ordNo>", "<sellerStockCd>70</sellerStockCd>")
                .doesNotContain("rcvrBaseAddr", "rcvrPrtblNo", "ordDlvReqCont", "memID", "ordNm", "rcvrNm");
    }

    @Test
    void sellerStockCdNotOurs_leavesOptionEmpty() {
        Map<String, String> row = paidRow();
        row.put("sellerStockCd", "ABC-1");
        given(orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(any(), any())).willReturn(Optional.empty());
        given(orderShipmentRepository.findByOrder_IdAndExternalShipmentId(any(), any())).willReturn(Optional.empty());
        given(elevenstOrderLineRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(any(), any(), any()))
                .willReturn(Optional.empty());

        upserter.upsertRows(account, ElevenstOrderList.PAID, List.of(row));

        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getProductListingOption()).isNull();
        verify(productListingOptionRepository, never()).findElevenstOrderOption(any(), any(), any());
    }

    @Test
    void shippingListRow_withoutExistingLine_createsNothing() {
        given(elevenstOrderLineRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(any(), any(), any()))
                .willReturn(Optional.empty());

        UpsertCount count = upserter.upsertRows(account, ElevenstOrderList.SHIPPING,
                List.of(Map.of("ordNo", "X", "ordPrdSeq", "1", "invcNo", "123")));

        assertThat(count.newCount()).isZero();
        verify(orderLineRepository, never()).save(any());
    }

    @Test
    void earlierList_neverMovesALineBack_D6() {
        OrderLine shipped = OrderLine.builder().id(1L).status(OrderStatus.SHIPPED).orderQty(2).cancelQty(0).holdQty(0)
                .build();
        ElevenstOrderLine mirror = ElevenstOrderLine.builder().id(9L).orderLine(shipped).platformStatus("401").build();
        given(elevenstOrderLineRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(any(), any(), any()))
                .willReturn(Optional.of(mirror));

        upserter.upsertRows(account, ElevenstOrderList.PREPARING, List.of(paidRow()));

        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(OrderStatus.SHIPPED);
        ArgumentCaptor<ElevenstOrderLine> savedMirror = ArgumentCaptor.forClass(ElevenstOrderLine.class);
        verify(elevenstOrderLineRepository).save(savedMirror.capture());
        assertThat(savedMirror.getValue().getPlatformStatus()).isEqualTo("401");
    }

    @Test
    void laterList_movesTheLineForward() {
        OrderLine paid = OrderLine.builder().id(1L).status(OrderStatus.PAID).orderQty(2).cancelQty(0).holdQty(0)
                .build();
        ElevenstOrderLine mirror = ElevenstOrderLine.builder().id(9L).orderLine(paid).platformStatus("202").build();
        given(elevenstOrderLineRepository.findByMarketplaceAccount_IdAndOrdNoAndOrdPrdSeq(any(), any(), any()))
                .willReturn(Optional.of(mirror));

        upserter.upsertRows(account, ElevenstOrderList.DELIVERED, List.of(Map.of("ordNo", "X", "ordPrdSeq", "1")));

        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(line.getValue().getOrderQty()).isEqualTo(2);
    }
}
