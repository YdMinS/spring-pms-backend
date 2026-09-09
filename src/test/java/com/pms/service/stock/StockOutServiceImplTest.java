package com.pms.service.stock;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.dto.request.OutboundConfirmRequest;
import com.pms.dto.response.OutboundResponse;
import com.pms.dto.response.StockOutSumView;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.StockMovementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * StockOutServiceImpl — 출고 대상 수량 규칙(D11·D12)과 확인 시 원장 행의 모양.
 *
 * <p>전개 자체는 {@link OrderLineExpanderTest} 가 본다. 여기서 지키는 것은 <b>수량</b>이다:
 * 필요 수량은 {@code orderQty} 에서 나오고, 소진은 {@code STOCK_OUT} 합계에서만 나온다.
 */
@ExtendWith(MockitoExtension.class)
class StockOutServiceImplTest {

    private static final Long LINE_ID = 11L;
    private static final Long PRODUCT_ID = 33L;
    private static final Long OTHER_PRODUCT_ID = 34L;
    private static final Long SELLER_ID = 5L;
    private static final LocalDate MOVED_ON = LocalDate.of(2026, 9, 9);
    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private ProductRepository productRepository;
    @Mock private OrderLineExpander orderLineExpander;

    @InjectMocks private StockOutServiceImpl service;

    // --- fixtures ---

    private static Product product(Long id, String name) {
        return Product.builder().id(id).productName(name).build();
    }

    private static OrderLine line(Long id, OrderStatus status, int orderQty, int cancelQty,
                                  LocalDateTime orderedAt, String externalOrderId) {
        Seller seller = Seller.builder().id(SELLER_ID).sellerName("셀러A").build();
        MarketplaceAccount account = MarketplaceAccount.builder().id(1L).seller(seller).build();
        Order order = Order.builder().id(7L).marketplaceAccount(account)
                .externalOrderId(externalOrderId).orderedAt(orderedAt).build();
        return OrderLine.builder().id(id).order(order).status(status)
                .itemName("양말A 3켤레").orderQty(orderQty).cancelQty(cancelQty).holdQty(0).build();
    }

    private static OrderLine line(OrderStatus status, int orderQty, int cancelQty) {
        return line(LINE_ID, status, orderQty, cancelQty, ORDERED_AT, "ORD-1");
    }

    private static OrderLineExpander.LineExpansion expansion(Long lineId, int quantity) {
        return new OrderLineExpander.LineExpansion(lineId,
                List.of(new OrderLineExpander.ExpandedProduct(PRODUCT_ID, "양말A", quantity)), null);
    }

    private void givenExpansion(OrderLineExpander.LineExpansion... expansions) {
        Map<Long, OrderLineExpander.LineExpansion> byLine = new java.util.LinkedHashMap<>();
        for (OrderLineExpander.LineExpansion e : expansions) {
            byLine.put(e.orderLineId(), e);
        }
        given(orderLineExpander.expand(anyCollection(), any())).willReturn(byLine);
    }

    private void givenStockOutSums(StockOutSumView... sums) {
        given(stockMovementRepository.findStockOutSums(anyCollection())).willReturn(List.of(sums));
    }

    private void givenSaveEchoes() {
        given(stockMovementRepository.save(any(StockMovement.class)))
                .willAnswer(inv -> inv.getArgument(0));
    }

    private static OutboundConfirmRequest confirmRequest(Long productId, int quantity) {
        return new OutboundConfirmRequest(LINE_ID,
                List.of(new OutboundConfirmRequest.ConfirmLine(productId, quantity)), MOVED_ON);
    }

    // --- confirm ---

    @Test
    void testConfirmStoresNegativeStockOut() {
        OrderLine line = line(OrderStatus.PAID, 3, 0);
        given(orderLineRepository.findWithListingOptionById(LINE_ID)).willReturn(Optional.of(line));
        givenExpansion(expansion(LINE_ID, 3));
        givenStockOutSums();
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(PRODUCT_ID, "양말A")));
        givenSaveEchoes();

        service.confirm(confirmRequest(PRODUCT_ID, 3));

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        StockMovement saved = captor.getValue();
        assertThat(saved.getMovementType()).isEqualTo(StockMovementType.STOCK_OUT);
        assertThat(saved.getQuantity()).isEqualTo(-3);
        assertThat(saved.getOrderLine().getId()).isEqualTo(LINE_ID);
        assertThat(saved.getSeller().getId()).isEqualTo(SELLER_ID);   // 주문에서 유도(2609_29 D4)
        assertThat(saved.getReason()).isNull();                        // STOCK_OUT 은 사유를 받지 않는다(D7)
        assertThat(saved.getUnitPrice()).isNull();                     // 출고 원가는 cost_basis 가 정한다
    }

    /** 확인 1건마다 원장 1행(D12) — 작업이 끊겨도 어디까지 했는지 복구된다. */
    @Test
    void testConfirmPerLineCreatesOneRowEach() {
        OrderLine line = line(OrderStatus.PAID, 1, 0);
        given(orderLineRepository.findWithListingOptionById(LINE_ID)).willReturn(Optional.of(line));
        given(orderLineExpander.expand(anyCollection(), any())).willReturn(Map.of(LINE_ID,
                new OrderLineExpander.LineExpansion(LINE_ID, List.of(
                        new OrderLineExpander.ExpandedProduct(PRODUCT_ID, "양말A", 2),
                        new OrderLineExpander.ExpandedProduct(OTHER_PRODUCT_ID, "장갑B", 1)), null)));
        givenStockOutSums();
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(PRODUCT_ID, "양말A")));
        given(productRepository.findById(OTHER_PRODUCT_ID))
                .willReturn(Optional.of(product(OTHER_PRODUCT_ID, "장갑B")));
        givenSaveEchoes();

        service.confirm(new OutboundConfirmRequest(LINE_ID, List.of(
                new OutboundConfirmRequest.ConfirmLine(PRODUCT_ID, 2),
                new OutboundConfirmRequest.ConfirmLine(OTHER_PRODUCT_ID, 1)), MOVED_ON));

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(m -> m.getProduct().getId(), StockMovement::getQuantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PRODUCT_ID, -2),
                        org.assertj.core.groups.Tuple.tuple(OTHER_PRODUCT_ID, -1));
    }

    @Test
    void testConfirmOverRequiredRejected() {
        OrderLine line = line(OrderStatus.PAID, 3, 0);
        given(orderLineRepository.findWithListingOptionById(LINE_ID)).willReturn(Optional.of(line));
        givenExpansion(expansion(LINE_ID, 3));
        givenStockOutSums();

        assertThatThrownBy(() -> service.confirm(confirmRequest(PRODUCT_ID, 4)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    /** 부분 확인 허용 — 남은 수량은 다음에 확인한다(D12). */
    @Test
    void testPartialConfirmThenRemaining() {
        OrderLine line = line(OrderStatus.PAID, 3, 0);
        given(orderLineRepository.findWithListingOptionById(LINE_ID)).willReturn(Optional.of(line));
        givenExpansion(expansion(LINE_ID, 3));
        // 이미 2 확인됨 → 남은 1
        givenStockOutSums(new StockOutSumView(LINE_ID, PRODUCT_ID, -2L));
        given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(PRODUCT_ID, "양말A")));
        givenSaveEchoes();

        assertThatThrownBy(() -> service.confirm(confirmRequest(PRODUCT_ID, 2)))
                .isInstanceOf(IllegalArgumentException.class);

        service.confirm(confirmRequest(PRODUCT_ID, 1));
        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualTo(-1);
    }

    @Test
    void testConfirmRejectedWhenExpansionFailed() {
        OrderLine line = line(OrderStatus.PAID, 3, 0);
        given(orderLineRepository.findWithListingOptionById(LINE_ID)).willReturn(Optional.of(line));
        givenExpansion(new OrderLineExpander.LineExpansion(LINE_ID, List.of(),
                OrderLineExpander.Failure.UNMAPPED_OPTION));

        assertThatThrownBy(() -> service.confirm(confirmRequest(PRODUCT_ID, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockMovementRepository, never()).save(any());
    }

    // --- outbound list ---

    @Test
    void testOutboundExcludesTerminalStatus() {
        // 상태 필터는 리포지토리가 하고(SHIPPED 는 애초에 안 온다), 전량 취소는 서비스가 거른다.
        OrderLine cancelled = line(LINE_ID, OrderStatus.PAID, 3, 3, ORDERED_AT, "ORD-1");
        given(orderLineRepository.findOutboundTargets(anyList(), any())).willReturn(List.of(cancelled));

        OutboundResponse response = service.outbound(null, null);

        assertThat(response.orders()).isEmpty();
        assertThat(response.unexpanded()).isEmpty();
        verify(orderLineExpander, never()).expand(anyCollection(), any());
    }

    /**
     * 🔴 이 기능의 핵심 회귀 테스트. 반품 동기화가 {@code cancelQty} 를 올려도
     * ({@code CoupangReturnSyncServiceImpl:369}) 남은 출고 수량은 변하지 않는다 — 소진의 정본은
     * {@code STOCK_OUT} 합계다(D12). 여기가 깨지면 반품이 재고를 자동 복원하기 시작한다.
     */
    @Test
    void testOutboundQuantityIgnoresCancelQty() {
        OrderLine returned = line(OrderStatus.PAID, 3, 2);   // 3 중 2 가 반품으로 cancelQty 에 올라옴
        given(orderLineRepository.findOutboundTargets(anyList(), any())).willReturn(List.of(returned));
        givenExpansion(expansion(LINE_ID, 3));
        givenStockOutSums();

        OutboundResponse response = service.outbound(null, null);

        assertThat(response.orders()).singleElement()
                .satisfies(o -> assertThat(o.products()).singleElement().satisfies(p -> {
                    assertThat(p.requiredQty()).isEqualTo(3);
                    assertThat(p.remainingQty()).isEqualTo(3);
                }));
    }

    /** 원장은 −3, 화면은 3. 부호를 뒤집는 책임은 서버 한 곳에 있다(웹·모바일이 갈리면 안 된다). */
    @Test
    void testConfirmedQtyReturnedAsPositive() {
        OrderLine line = line(OrderStatus.PAID, 5, 0);
        given(orderLineRepository.findOutboundTargets(anyList(), any())).willReturn(List.of(line));
        givenExpansion(expansion(LINE_ID, 5));
        givenStockOutSums(new StockOutSumView(LINE_ID, PRODUCT_ID, -3L));

        OutboundResponse response = service.outbound(null, null);

        assertThat(response.orders()).singleElement()
                .satisfies(o -> assertThat(o.products()).singleElement().satisfies(p -> {
                    assertThat(p.confirmedQty()).isEqualTo(3);
                    assertThat(p.remainingQty()).isEqualTo(2);
                }));
    }

    @Test
    void testOutboundSortedByOrderedAtAsc() {
        OrderLine older = line(LINE_ID, OrderStatus.PAID, 1, 0, ORDERED_AT.minusDays(3), "ORD-OLD");
        OrderLine newer = line(12L, OrderStatus.PAID, 1, 0, ORDERED_AT, "ORD-NEW");
        // 정렬은 쿼리가 한다 — 서비스는 그 순서를 흐트러뜨리지 않는다.
        given(orderLineRepository.findOutboundTargets(anyList(), any())).willReturn(List.of(older, newer));
        givenExpansion(expansion(LINE_ID, 1), expansion(12L, 1));
        givenStockOutSums();

        OutboundResponse response = service.outbound(null, null);

        assertThat(response.orders()).extracting(o -> o.externalOrderId())
                .containsExactly("ORD-OLD", "ORD-NEW");
    }

    /** 전개 실패는 목록에서 사라지지 않고 unexpanded 로 나온다(D13). */
    @Test
    void testOutboundReportsUnexpandedLines() {
        OrderLine line = line(OrderStatus.PAID, 3, 0);
        given(orderLineRepository.findOutboundTargets(anyList(), any())).willReturn(List.of(line));
        givenExpansion(new OrderLineExpander.LineExpansion(LINE_ID, List.of(),
                OrderLineExpander.Failure.NO_MASTER_OPTION));

        OutboundResponse response = service.outbound(null, null);

        assertThat(response.orders()).isEmpty();
        assertThat(response.unexpanded()).singleElement().satisfies(u -> {
            assertThat(u.orderLineId()).isEqualTo(LINE_ID);
            assertThat(u.reason()).isEqualTo("NO_MASTER_OPTION");
            assertThat(u.externalOrderId()).isEqualTo("ORD-1");
        });
    }

    @Test
    void testOutboundRejectsTerminalStatusFilter() {
        assertThatThrownBy(() -> service.outbound(null, OrderStatus.SHIPPED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
