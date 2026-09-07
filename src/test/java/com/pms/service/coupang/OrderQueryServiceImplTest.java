package com.pms.service.coupang;

import com.pms.config.CoupangProperties;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.dto.response.OrderItemResponse;
import com.pms.dto.response.OrderMonthResponse;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * OrderQueryServiceImpl — sellerId 분기, 엔티티→DTO 매핑(purchasableQty, raw 미노출).
 * 기간 미지정 시 syncDays 윈도우(orders.ordered_at 기준)로 제한되고, from/to 를 주면 그 범위를 쓴다.
 * 여기서는 분기/경계/매핑을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderQueryServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CoupangOrderLineRepository coupangOrderLineRepository;
    @Mock private CoupangProperties coupangProperties;
    @InjectMocks private OrderQueryServiceImpl service;

    private OrderLine line(Long id, OrderStatus status, int orderQty, int cancelQty, int holdQty) {
        MarketplaceAccount acc = MarketplaceAccount.builder().id(7L).platform(Platform.COUPANG).build();
        Order order = Order.builder()
                .id(100L).marketplaceAccount(acc).platform(Platform.COUPANG)
                .externalOrderId("O1").orderedAt(LocalDateTime.of(2026, 8, 1, 9, 0))
                .ordererName("홍길동").receiverName("김철수").build();
        OrderShipment shipment = OrderShipment.builder().id(200L).order(order)
                .externalShipmentId("B1").build();
        return OrderLine.builder()
                .id(id).order(order).orderShipment(shipment)
                .status(status).itemName("양말")
                .orderQty(orderQty).cancelQty(cancelQty).holdQty(holdQty)
                .build();
    }

    private CoupangOrderLine mirror(OrderLine line, String platformStatus) {
        return CoupangOrderLine.builder()
                .id(line.getId() + 1000).orderLine(line)
                .shipmentBoxId("B1").orderIdRaw("O1").vendorItemId("I1")
                .platformStatus(platformStatus).raw("{\"big\":\"json\"}")
                .build();
    }

    private OrderLine sample() {
        return line(1L, OrderStatus.PAID, 10, 2, 1);
    }

    @Test
    void list_filtersBySeller() {
        given(coupangProperties.getSyncDays()).willReturn(14);
        OrderLine sample = sample();
        given(orderRepository.findRecentOrdersBySeller(eq(5L), any(LocalDateTime.class)))
                .willReturn(List.of(sample));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(sample, "ACCEPT")));

        service.list(5L, null, null);

        verify(orderRepository).findRecentOrdersBySeller(eq(5L), any(LocalDateTime.class));
        verify(orderRepository, never()).findRecentOrders(any(LocalDateTime.class));
    }

    @Test
    void list_mapsPurchasableQty() {
        given(coupangProperties.getSyncDays()).willReturn(14);
        OrderLine sample = sample();
        given(orderRepository.findRecentOrders(any(LocalDateTime.class))).willReturn(List.of(sample));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(sample, "ACCEPT")));

        List<OrderItemResponse> result = service.list(null, null, null);   // null → 전체

        verify(orderRepository).findRecentOrders(any(LocalDateTime.class));
        OrderItemResponse r = result.get(0);
        assertThat(r.getPurchasableQty()).isEqualTo(7);        // 10-(2+1)
        assertThat(r.getExternalItemId()).isEqualTo("I1");     // 거울의 vendorItemId
        assertThat(r.getExternalBoxId()).isEqualTo("B1");      // 배송 묶음 식별자
        assertThat(r.getOrdererName()).isEqualTo("홍길동");
        assertThat(r.getReceiverName()).isEqualTo("김철수");
        assertThat(r.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 9, 0));
        // raw 필드는 DTO에 존재하지 않음 → 직렬화/노출 불가 (목록 가벼움)
    }

    @Test
    void list_partialCancel_keepsNeutralStatus() {
        // 일부만 취소 → 중립 상태 유지 + 원문 병기, cancelled=false
        given(coupangProperties.getSyncDays()).willReturn(14);
        OrderLine sample = sample();
        given(orderRepository.findRecentOrders(any(LocalDateTime.class))).willReturn(List.of(sample));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(sample, "ACCEPT")));

        OrderItemResponse r = service.list(null, null, null).get(0);       // orderQty 10, cancel 2, hold 1
        assertThat(r.getStatus()).isEqualTo("PAID");
        assertThat(r.getPlatformStatus()).isEqualTo("ACCEPT");
        assertThat(r.isCancelled()).isFalse();
    }

    @Test
    void list_fullyCancelledPreparing_mapsToCancelled() {
        // 상품준비중(PREPARING)인데 전량 취소 → status=CANCELLED(서버 파생), 원문은 그대로 병기
        given(coupangProperties.getSyncDays()).willReturn(14);
        OrderLine fully = line(2L, OrderStatus.PREPARING, 3, 3, 0);
        given(orderRepository.findRecentOrders(any(LocalDateTime.class))).willReturn(List.of(fully));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(fully, "INSTRUCT")));

        OrderItemResponse r = service.list(null, null, null).get(0);
        assertThat(r.getStatus()).isEqualTo("CANCELLED");
        assertThat(r.getPlatformStatus()).isEqualTo("INSTRUCT");   // 원문 보존
        assertThat(r.isCancelled()).isTrue();
        assertThat(r.getPurchasableQty()).isZero();
    }

    @Test
    void list_noPeriod_usesDefaultWindow() {
        given(coupangProperties.getSyncDays()).willReturn(14);
        OrderLine sample = sample();
        given(orderRepository.findRecentOrders(any(LocalDateTime.class))).willReturn(List.of(sample));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(sample, "ACCEPT")));

        service.list(null, null, null);

        verify(orderRepository).findRecentOrders(any(LocalDateTime.class));
        verify(orderRepository, never()).findOrdersInPeriod(any(LocalDateTime.class), any(LocalDateTime.class));
        verify(coupangProperties).getSyncDays();
    }

    @Test
    void list_withPeriod_usesRangeQuery_endExclusive() {
        // to 당일이 포함되도록 상한은 to+1일 00:00 (배타적) 이어야 한다 — 이 경계가 2609_08 D4 의 핵심
        OrderLine sample = sample();
        given(orderRepository.findOrdersInPeriod(any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of(sample));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(sample, "ACCEPT")));

        service.list(null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).findOrdersInPeriod(fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    @Test
    void list_withPeriodAndSeller_usesSellerRangeQuery() {
        OrderLine sample = sample();
        given(orderRepository.findOrdersInPeriodBySeller(
                eq(5L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of(sample));
        given(coupangOrderLineRepository.findByOrderLine_IdIn(anyList()))
                .willReturn(List.of(mirror(sample, "ACCEPT")));

        service.list(5L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        verify(orderRepository).findOrdersInPeriodBySeller(
                eq(5L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(orderRepository, never()).findOrdersInPeriod(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void list_withPeriod_doesNotReadSyncDays() {
        // 기간을 주면 기본 창 로직이 섞이지 않아야 한다
        given(orderRepository.findOrdersInPeriod(any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of());

        service.list(null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        verify(coupangProperties, never()).getSyncDays();
    }

    @Test
    void list_emptyResult_doesNotQueryMirrors() {
        // 빈 목록으로 IN 을 날리지 않는다.
        given(orderRepository.findOrdersInPeriod(any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of());

        assertThat(service.list(null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))).isEmpty();

        verifyNoInteractions(coupangOrderLineRepository);
    }

    @Test
    void list_invalidPeriod_throws() {
        LocalDate day = LocalDate.of(2026, 8, 1);

        assertThatThrownBy(() -> service.list(null, day, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(null, null, day))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(null, LocalDate.of(2026, 8, 31), day))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(orderRepository);
    }

    @Test
    void months_mapsYearMonthToYm() {
        // YEAR()/MONTH()/COUNT() 의 반환 타입은 Hibernate·DB 조합에 따라 다르므로 Number 로 받는다
        given(orderRepository.countByMonth())
                .willReturn(List.<Object[]>of(new Object[]{2026, 5, 12L}));

        List<OrderMonthResponse> result = service.months();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ym()).isEqualTo("2026-05");   // 0-패딩
        assertThat(result.get(0).count()).isEqualTo(12);
    }

    @Test
    void months_empty_returnsEmptyList() {
        given(orderRepository.countByMonth()).willReturn(List.of());

        assertThat(service.months()).isEmpty();
    }
}
