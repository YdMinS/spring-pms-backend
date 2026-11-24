package com.pms.service.stock;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderClaim;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import com.pms.dto.request.ClaimWorkRequest;
import com.pms.dto.request.StockMovementRequest;
import com.pms.dto.response.ClaimProductSumView;
import com.pms.dto.response.ClaimWorkView;
import com.pms.dto.response.ShippedOrderView;
import com.pms.exception.BusinessException;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.OrderClaimRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.repository.StockMovementRepository;
import com.pms.service.stock.OrderLineExpander.ExpandedProduct;
import com.pms.service.stock.OrderLineExpander.Failure;
import com.pms.service.stock.OrderLineExpander.LineExpansion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 「반품 재입고」 · 「교환 발송」 lookups and saves (FEATURE_2611_34 D18 ~ D22 · D26 · D27 · D29 · D31 · D42 · R6 ~ R8).
 */
@ExtendWith(MockitoExtension.class)
class ClaimWorkServiceImplTest {

    @Mock private OrderClaimRepository orderClaimRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private ShipmentParcelRepository shipmentParcelRepository;
    @Mock private ProductRepository productRepository;
    @Mock private OrderLineExpander orderLineExpander;
    @Mock private WorkLineRecorder workLineRecorder;

    @InjectMocks private ClaimWorkServiceImpl service;

    private static final String IMAGE = "https://bucket/p7.png";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    private final Seller seller = Seller.builder().id(42L).sellerName("셀러C").build();
    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder()
            .id(1L).seller(seller).accountAlias("쿠팡A").build();
    private final Order order50 = order(50L, LocalDateTime.of(2026, 11, 20, 10, 0));
    private final OrderLine line5 = line(5L, order50);
    private final Product product7 = Product.builder().id(7L).productName("양말A").barcodeId("880001")
            .imageUrl(IMAGE).build();

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Order order(Long id, LocalDateTime orderedAt) {
        return Order.builder().id(id).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("ORD-" + id).orderedAt(orderedAt).build();
    }

    private OrderLine line(Long id, Order order) {
        return OrderLine.builder().id(id).order(order).status(OrderStatus.DELIVERED).itemName("양말A 3켤레")
                .orderQty(1).cancelQty(0).holdQty(0).build();
    }

    private OrderClaim claim(Long id, ClaimType type, int quantity, OrderLine line, ClaimStatus status) {
        return OrderClaim.builder().id(id).marketplaceAccount(account).platform(Platform.COUPANG)
                .claimType(type).externalClaimId("C-" + id).externalOrderId("ORD-1").externalItemId("vi-1")
                .orderLine(line).orderItemMatchAttempts(0).itemName("양말A 3켤레").quantity(quantity)
                .status(status).platformStatus("RU").collectStatus("RU").requesterName("홍길동")
                .receivedAt(LocalDateTime.of(2026, 11, 20, 10, 0)).syncedAt(LocalDateTime.of(2026, 11, 20, 10, 0))
                .build();
    }

    /** 건 21 = RETURN · quantity 2 · order line 5 · RECEIVED. */
    private OrderClaim claim21() {
        return claim(21L, ClaimType.RETURN, 2, line5, ClaimStatus.RECEIVED);
    }

    private void givenBom(Long lineId, int quantity) {
        given(orderLineExpander.expand(anyCollection(), any())).willReturn(Map.of(lineId,
                new LineExpansion(lineId, List.of(new ExpandedProduct(7L, "양말A", quantity, IMAGE)), null)));
        given(productRepository.findAllById(anyCollection())).willReturn(List.of(product7));
    }

    private void givenFailure(Long lineId, Failure failure) {
        given(orderLineExpander.expand(anyCollection(), any()))
                .willReturn(Map.of(lineId, new LineExpansion(lineId, List.of(), failure)));
    }

    private void givenReturnSums(ClaimProductSumView... sums) {
        given(stockMovementRepository.findClaimProductSums(anyCollection(), eq(StockMovementType.RETURN_IN), isNull()))
                .willReturn(Arrays.asList(sums));
    }

    private void givenExchangeSums(ClaimProductSumView... sums) {
        given(stockMovementRepository.findClaimProductSums(anyCollection(), eq(StockMovementType.DISPOSAL),
                eq(StockReason.EXCHANGE_SHIP))).willReturn(Arrays.asList(sums));
    }

    private ClaimWorkRequest request(ClaimWorkRequest.Line... lines) {
        return new ClaimWorkRequest(DAY, List.of(lines));
    }

    private static ClaimWorkRequest.Line req(long claimId, long productId, int quantity) {
        return new ClaimWorkRequest.Line(claimId, productId, quantity);
    }

    private StockMovementRequest recorded() {
        ArgumentCaptor<StockMovementRequest> captor = ArgumentCaptor.forClass(StockMovementRequest.class);
        verify(workLineRecorder).record(captor.capture());
        return captor.getValue();
    }

    // ── ① 반품 송장 ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void testByReturnInvoiceNormalizesAndBuildsItems() {
        given(orderClaimRepository.findWorkByCollectInvoice("123456789012")).willReturn(List.of(claim21()));
        givenBom(5L, 3);
        givenReturnSums(new ClaimProductSumView(21L, 7L, 1));

        List<ClaimWorkView> views = service.byReturnInvoice("1234-5678 9012");

        assertThat(views).hasSize(1);
        ClaimWorkView view = views.get(0);
        assertThat(view.failure()).isNull();
        assertThat(view.sellerName()).isEqualTo("셀러C");
        ClaimWorkView.Item item = view.items().get(0);
        assertThat(item.requiredQty()).isEqualTo(6);
        assertThat(item.doneQty()).isEqualTo(1);
        assertThat(item.remainingQty()).isEqualTo(5);
        assertThat(item.barcode()).isEqualTo("880001");

        ArgumentCaptor<ToIntFunction<OrderLine>> unit = ArgumentCaptor.forClass(ToIntFunction.class);
        verify(orderLineExpander).expand(anyCollection(), unit.capture());
        assertThat(unit.getValue().applyAsInt(line5)).isEqualTo(1);
    }

    @Test
    void testByReturnInvoiceNotFound() {
        assertThatThrownBy(() -> service.byReturnInvoice("1234-5678-9012"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("반품 송장을 찾을 수 없습니다: 123456789012 — 반품/교환 화면에서 [동기화]를 먼저 누르세요");
    }

    @Test
    void testUnlinkedClaimFailure() {
        Seller other = Seller.builder().id(43L).sellerName("셀러D").build();
        MarketplaceAccount otherAccount = MarketplaceAccountFixture.coupangStubBuilder()
                .id(2L).seller(other).accountAlias("쿠팡B").build();
        OrderClaim unlinked = claim21().toBuilder().orderLine(null).marketplaceAccount(otherAccount).build();
        given(orderClaimRepository.findWorkByCollectInvoice("123456789012")).willReturn(List.of(unlinked));

        ClaimWorkView view = service.byReturnInvoice("123456789012").get(0);

        assertThat(view.failure()).isEqualTo("UNLINKED");
        assertThat(view.items()).isEmpty();
    }

    @Test
    void testExpansionFailureCarried() {
        given(orderClaimRepository.findWorkByCollectInvoice("123456789012")).willReturn(List.of(claim21()));
        givenFailure(5L, Failure.NO_MASTER_OPTION);

        ClaimWorkView view = service.byReturnInvoice("123456789012").get(0);

        assertThat(view.failure()).isEqualTo("NO_MASTER_OPTION");
        assertThat(view.items()).isEmpty();
    }

    // ── ② 출고 송장 ──────────────────────────────────────────────────────────

    @Test
    void testByParcelInvoiceParcelNotFound() {
        assertThatThrownBy(() -> service.byParcelInvoice("123456789012"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("송장번호를 찾을 수 없습니다: 123456789012");
    }

    @Test
    void testByParcelInvoiceNoClaims() {
        ShipmentParcel parcel = ShipmentParcel.builder().id(1L)
                .orderShipment(OrderShipment.builder().id(500L).build()).invoiceNumber("123456789012").build();
        given(shipmentParcelRepository.findByInvoiceNumberOrderByIdAsc("123456789012")).willReturn(List.of(parcel));
        given(orderLineRepository.findByOrderShipment_Id(500L)).willReturn(List.of(line5));
        given(orderClaimRepository.findWorkByOrderLineIds(List.of(5L))).willReturn(List.of());

        assertThatThrownBy(() -> service.byParcelInvoice("123456789012"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("이 주문에 반품 · 교환 건이 없습니다 — 반품/교환 화면에서 [동기화]를 먼저 누르세요");
    }

    // ── ③ 최근 출고 주문 ─────────────────────────────────────────────────────

    private void givenShippedOrders() {
        Order order51 = order(51L, LocalDateTime.of(2026, 11, 18, 10, 0));
        OrderLine line6 = line(6L, order51);
        given(orderLineRepository.findShippedSince(any(), any())).willReturn(List.of(line5, line6));
        given(orderClaimRepository.findWorkByOrderLineIds(List.of(5L, 6L)))
                .willReturn(List.of(claim(22L, ClaimType.RETURN, 1, line6, ClaimStatus.RECEIVED)));
        givenBom(6L, 5);
    }

    @Test
    void testShippedOrdersPendingFirst() {
        givenShippedOrders();

        List<ShippedOrderView> orders = service.shippedOrders();

        assertThat(orders).extracting(ShippedOrderView::orderId, ShippedOrderView::claimPending)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(51L, true),
                        org.assertj.core.groups.Tuple.tuple(50L, false));
    }

    @Test
    void testShippedOrdersDoneClaimNotPending() {
        givenShippedOrders();
        givenReturnSums(new ClaimProductSumView(22L, 7L, 5));

        List<ShippedOrderView> orders = service.shippedOrders();

        assertThat(orders).filteredOn(order -> order.orderId().equals(51L))
                .singleElement()
                .satisfies(order -> assertThat(order.claimPending()).isFalse());
    }

    // ── ④ 반품 재입고 저장 ───────────────────────────────────────────────────

    /** 건 21: BOM 3 × quantity 2 = 6, 1 already in → 5 remaining. */
    private void givenClaim21Remaining5() {
        given(orderClaimRepository.findWorkByIdIn(any())).willReturn(List.of(claim21()));
        givenBom(5L, 3);
        givenReturnSums(new ClaimProductSumView(21L, 7L, 1));
    }

    @Test
    void testReturnInRecordsEachLine() {
        givenClaim21Remaining5();

        service.returnIn(request(req(21, 7, 2)));

        verify(workLineRecorder).requireStockLinked();
        StockMovementRequest saved = recorded();
        assertThat(saved.movementType()).isEqualTo(StockMovementType.RETURN_IN);
        assertThat(saved.productId()).isEqualTo(7L);
        assertThat(saved.quantity()).isEqualTo(2);
        assertThat(saved.orderClaimId()).isEqualTo(21L);
        assertThat(saved.sellerId()).isNull();
        assertThat(saved.reason()).isNull();
        assertThat(saved.movedOn()).isEqualTo(DAY);
    }

    @Test
    void testReturnInOverRemainingRejected() {
        givenClaim21Remaining5();

        assertThatThrownBy(() -> service.returnIn(request(req(21, 7, 3), req(21, 7, 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("양말A은 더 받을 수 없습니다(남은 5개)");
        verify(workLineRecorder, never()).record(any());
    }

    @Test
    void testReturnInProductNotInClaimRejected() {
        givenClaim21Remaining5();

        assertThatThrownBy(() -> service.returnIn(request(req(21, 8, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이 반품에 없는 물건입니다: 8");
        verify(workLineRecorder, never()).record(any());
    }

    @Test
    void testReturnInUnexpandableRejected() {
        given(orderClaimRepository.findWorkByIdIn(any())).willReturn(List.of(claim21()));
        givenFailure(5L, Failure.EMPTY_BOM);

        assertThatThrownBy(() -> service.returnIn(request(req(21, 7, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이 반품은 받을 물건을 알 수 없습니다 — 입고·조정 화면의 반품 대기에서 기록하세요");
        verify(workLineRecorder, never()).record(any());
    }

    // ── ⑦ 교환 발송 저장 ─────────────────────────────────────────────────────

    @Test
    void testExchangeShipUnexpandableRejected() {
        given(orderClaimRepository.findWorkByIdIn(any()))
                .willReturn(List.of(claim(22L, ClaimType.EXCHANGE, 1, line5, ClaimStatus.RECEIVED)));
        givenFailure(5L, Failure.EMPTY_BOM);

        assertThatThrownBy(() -> service.exchangeShip(request(req(22, 7, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이 교환은 보낼 물건을 알 수 없습니다 — 판매상품의 옵션 연결을 고친 뒤 다시 여세요");
        verify(workLineRecorder, never()).record(any());
    }

    @Test
    void testExchangeShipRecordsDisposalWithClaim() {
        given(orderClaimRepository.findWorkByIdIn(any()))
                .willReturn(List.of(claim(22L, ClaimType.EXCHANGE, 1, line5, ClaimStatus.RECEIVED)));
        givenBom(5L, 2);
        givenExchangeSums(new ClaimProductSumView(22L, 7L, -1));

        service.exchangeShip(request(req(22, 7, 1)));

        verify(stockMovementRepository).findClaimProductSums(anyCollection(), eq(StockMovementType.DISPOSAL),
                eq(StockReason.EXCHANGE_SHIP));
        StockMovementRequest saved = recorded();
        assertThat(saved.movementType()).isEqualTo(StockMovementType.DISPOSAL);
        assertThat(saved.reason()).isEqualTo(StockReason.EXCHANGE_SHIP);
        assertThat(saved.quantity()).isEqualTo(1);
        assertThat(saved.orderClaimId()).isEqualTo(22L);
        assertThat(saved.sellerId()).isNull();
    }

    // ── ⑥ 교환 찾기 ──────────────────────────────────────────────────────────

    @Test
    void testOpenExchangesKeepsPendingOnly() {
        given(orderClaimRepository.findWorkByClaimType(ClaimType.EXCHANGE)).willReturn(List.of(
                claim(22L, ClaimType.EXCHANGE, 1, line5, ClaimStatus.RECEIVED),
                claim(23L, ClaimType.EXCHANGE, 1, line5, ClaimStatus.RECEIVED)));
        givenBom(5L, 1);
        givenExchangeSums(new ClaimProductSumView(22L, 7L, -1));

        assertThat(service.openExchanges()).extracting(ClaimWorkView::orderClaimId).containsExactly(23L);
    }

    @Test
    void testOpenExchangesExcludesDoneOnly() {
        given(orderClaimRepository.findWorkByClaimType(ClaimType.EXCHANGE)).willReturn(List.of(
                claim(24L, ClaimType.EXCHANGE, 1, line5, ClaimStatus.DONE),
                claim(25L, ClaimType.EXCHANGE, 1, line5, ClaimStatus.WITHDRAWN),
                claim(26L, ClaimType.EXCHANGE, 1, line5, ClaimStatus.REJECTED)));
        givenBom(5L, 1);

        assertThat(service.openExchanges()).extracting(ClaimWorkView::orderClaimId).containsExactly(25L, 26L);
    }

    @Test
    void testByReshipInvoiceNotFound() {
        assertThatThrownBy(() -> service.byReshipInvoice("5555-6666"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("교환 송장을 찾을 수 없습니다: 55556666 — 반품/교환 화면에서 [동기화]를 먼저 누르세요");
    }
}
