package com.pms.service.order;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Package;
import com.pms.domain.ParcelStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.Seller;
import com.pms.domain.ShipmentParcel;
import com.pms.domain.ShipmentParcelItem;
import com.pms.dto.response.OrderDetailResponse;
import com.pms.dto.response.OrderItemResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShipmentParcelRepository;
import com.pms.service.coupang.OrderQueryService;
import com.pms.service.stock.OrderLineExpander;
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
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * The shared order detail read (FEATURE_2611_07 / PLAN D17 · D23 · D29 · D30 · D31 · D36 ~ D38) and the master product
 * · master option · components of every line (FEATURE_2611_15 / D1 · D3 · D4 · D5 · D14 · D18).
 */
@ExtendWith(MockitoExtension.class)
class OrderDetailServiceImplTest {

    private static final Long ORDER_ID = 7L;
    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 11, 3, 9, 15);

    @Mock private OrderLineRepository orderLineRepository;
    @Mock private OrderShipmentRepository orderShipmentRepository;
    @Mock private ShipmentParcelRepository parcelRepository;
    @Mock private ShipmentParcelItemRepository parcelItemRepository;
    @Mock private OrderQueryService orderQueryService;
    @Mock private OrderLineExpander orderLineExpander;
    @Mock private MasterProductOptionRepository masterProductOptionRepository;

    @InjectMocks private OrderDetailServiceImpl service;

    private final Order order = order();
    private final OrderShipment first = OrderShipment.builder().id(500L).order(order).externalShipmentId("BOX-1")
            .build();
    private final OrderShipment second = OrderShipment.builder().id(501L).order(order).externalShipmentId("BOX-2")
            .build();
    private final OrderLine lineA = line(11L, "양말A 3켤레");
    private final OrderLine lineB = line(12L, "양말B 5켤레");
    private final List<OrderItemResponse> rows = List.of(
            OrderItemResponse.builder().id(11L).itemName("양말A 3켤레").build(),
            OrderItemResponse.builder().id(12L).itemName("양말B 5켤레").build());

    @Test
    void testByOrderLineReturnsLinesSellerAliasAndParcelsByStatus() {
        Package box = Package.builder().id(70L).type("3호 상자").build();
        ShipmentParcel packed = parcel(900L, first, 1, ParcelStatus.PACKED, LocalDateTime.of(2026, 11, 3, 1, 30), box);
        // D37: an unused parcel keeps its processed time but never shows a box — even when one is stored.
        ShipmentParcel unused = parcel(901L, first, 2, ParcelStatus.UNUSED, LocalDateTime.of(2026, 11, 3, 2, 0), box);
        ShipmentParcel pending = parcel(902L, second, 1, ParcelStatus.PENDING, null, null);
        Product socksA = Product.builder().id(33L).productName("양말A").imageUrl("products/33.png").build();
        Product socksB = Product.builder().id(34L).productName("양말B").imageUrl(null).build();
        given(orderLineRepository.findWithAccountAndSellerById(11L)).willReturn(Optional.of(lineA));
        given(orderLineRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of(lineA, lineB));
        given(orderQueryService.rows(List.of(lineA, lineB))).willReturn(rows);
        given(orderShipmentRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of(first, second));
        // Repository order is not the screen order — the service sorts bundle → sequence (D29).
        given(parcelRepository.findWithBoxByOrderShipment_IdIn(List.of(500L, 501L)))
                .willReturn(List.of(pending, unused, packed));
        given(parcelItemRepository.findByShipmentParcel_IdInOrderByIdAsc(List.of(900L))).willReturn(List.of(
                item(1L, packed, lineA, socksA, 2),
                item(2L, packed, lineB, socksB, 1),
                item(3L, packed, lineB, socksA, 1)));

        OrderDetailResponse detail = service.byOrderLine(11L);

        assertThat(detail.externalOrderId()).isEqualTo("ORD-1");
        assertThat(detail.sellerName()).isEqualTo("셀러A");
        assertThat(detail.platform()).isEqualTo("COUPANG");
        assertThat(detail.accountAlias()).isEqualTo("본계정");
        assertThat(detail.ordererName()).isEqualTo("김주문");
        assertThat(detail.receiverName()).isEqualTo("박수취");
        assertThat(detail.paidAt()).isEqualTo(ORDERED_AT);
        assertThat(detail.lines()).isSameAs(rows);
        assertThat(detail.parcels()).extracting(OrderDetailResponse.ParcelView::parcelId)
                .containsExactly(900L, 901L, 902L);

        OrderDetailResponse.ParcelView packedView = detail.parcels().get(0);
        assertThat(packedView.invoiceNumber()).isEqualTo("INV-900");
        assertThat(packedView.carrierName()).isEqualTo("롯데택배");
        assertThat(packedView.parcelSeq()).isEqualTo(1);
        assertThat(packedView.status()).isEqualTo(ParcelStatus.PACKED);
        // D38: stored UTC 01:30 = KST 10:30.
        assertThat(packedView.packedAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 10, 30));
        assertThat(packedView.boxName()).isEqualTo("3호 상자");
        assertThat(packedView.boxPackageId()).isEqualTo(70L);
        // The same product from two lines is one row (2 + 1), first appearance order.
        assertThat(packedView.items()).containsExactly(
                new OrderDetailResponse.ParcelItemView(33L, "양말A", "products/33.png", 3),
                new OrderDetailResponse.ParcelItemView(34L, "양말B", null, 1));

        OrderDetailResponse.ParcelView unusedView = detail.parcels().get(1);
        assertThat(unusedView.status()).isEqualTo(ParcelStatus.UNUSED);
        assertThat(unusedView.packedAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 11, 0));
        assertThat(unusedView.boxName()).isNull();
        assertThat(unusedView.boxPackageId()).isNull();
        assertThat(unusedView.items()).isEmpty();

        OrderDetailResponse.ParcelView pendingView = detail.parcels().get(2);
        assertThat(pendingView.status()).isEqualTo(ParcelStatus.PENDING);
        assertThat(pendingView.packedAt()).isNull();
        assertThat(pendingView.boxName()).isNull();
        assertThat(pendingView.boxPackageId()).isNull();
        assertThat(pendingView.items()).isEmpty();
    }

    @Test
    void testByParcelReturnsTheSameOrderDetail() {
        ShipmentParcel packed = parcel(900L, first, 1, ParcelStatus.PACKED, LocalDateTime.of(2026, 11, 3, 1, 30),
                Package.builder().id(70L).type("3호 상자").build());
        Product socksA = Product.builder().id(33L).productName("양말A").imageUrl("products/33.png").build();
        given(orderLineRepository.findWithAccountAndSellerById(11L)).willReturn(Optional.of(lineA));
        given(parcelRepository.findWithOrderById(900L)).willReturn(Optional.of(packed));
        given(orderLineRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of(lineA, lineB));
        given(orderQueryService.rows(List.of(lineA, lineB))).willReturn(rows);
        given(orderShipmentRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of(first));
        given(parcelRepository.findWithBoxByOrderShipment_IdIn(List.of(500L))).willReturn(List.of(packed));
        given(parcelItemRepository.findByShipmentParcel_IdInOrderByIdAsc(List.of(900L)))
                .willReturn(List.of(item(1L, packed, lineA, socksA, 2)));
        // FEATURE_2611_15 / D14: the parcel read gives the same master · option · components.
        given(orderLineExpander.expand(eq(List.of(lineA, lineB)), any())).willReturn(Map.of(
                11L, linked(11L, new OrderLineExpander.ExpandedProduct(33L, "양말A", 2, "products/33.png")),
                12L, new OrderLineExpander.LineExpansion(12L, List.of(), OrderLineExpander.Failure.EMPTY_BOM)));
        given(masterProductOptionRepository.findWithMasterProductByIdIn(List.of(MASTER_OPTION_ID)))
                .willReturn(List.of(masterOption()));

        OrderDetailResponse byParcel = service.byParcel(900L);

        assertThat(byParcel).isEqualTo(service.byOrderLine(11L));
        assertThat(byParcel.lineMasters()).extracting(OrderDetailResponse.LineMaster::masterProductName)
                .containsExactly("수세미 세트", null);
    }

    /** A linked line names its master product and master option and lists every component × purchasable (D3 · D18). */
    @Test
    void testLinkedLineHasMasterOptionAndComponentsTimesPurchasableQty() {
        // Ordered 5, cancelled 1, refund-hold 1 → purchasable 3 (D18).
        OrderLine linkedLine = OrderLine.builder().id(11L).order(order).orderShipment(first)
                .status(OrderStatus.PAID).itemName("수세미 2종").orderQty(5).cancelQty(1).holdQty(1).build();
        given(orderLineRepository.findWithAccountAndSellerById(11L)).willReturn(Optional.of(linkedLine));
        given(orderLineRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of(linkedLine));
        given(orderQueryService.rows(List.of(linkedLine))).willReturn(List.of());
        given(orderShipmentRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of());
        given(orderLineExpander.expand(eq(List.of(linkedLine)), any())).willReturn(Map.of(11L, linked(11L,
                new OrderLineExpander.ExpandedProduct(41L, "노란 수세미", 3, "products/41.png"),
                new OrderLineExpander.ExpandedProduct(42L, "초록 수세미", 6, null))));
        given(masterProductOptionRepository.findWithMasterProductByIdIn(List.of(MASTER_OPTION_ID)))
                .willReturn(List.of(masterOption()));

        OrderDetailResponse detail = service.byOrderLine(11L);

        assertThat(detail.lineMasters()).containsExactly(new OrderDetailResponse.LineMaster(11L, 80L, "수세미 세트",
                "2종 묶음", List.of(
                        new OrderDetailResponse.LineProduct(41L, "노란 수세미", "products/41.png", 3),
                        new OrderDetailResponse.LineProduct(42L, "초록 수세미", null, 6)),
                null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ToIntFunction<OrderLine>> unitCount = ArgumentCaptor.forClass(ToIntFunction.class);
        verify(orderLineExpander).expand(eq(List.of(linkedLine)), unitCount.capture());
        assertThat(unitCount.getValue().applyAsInt(linkedLine)).isEqualTo(3);
        // No authentication in a unit test = not an admin (D20).
        assertThat(detail.admin()).isFalse();
    }

    /** A broken link carries only the reason — the outbound confirm screen's codes (D5). */
    @Test
    void testBrokenLinksCarryOnlyTheReason() {
        OrderLine unmapped = line(21L, "옵션 모름");
        OrderLine noMaster = line(22L, "채널 전용 옵션");
        OrderLine emptyBom = line(23L, "구성 없음");
        OrderLine notExpanded = line(24L, "전개 결과 없음");
        List<OrderLine> lines = List.of(unmapped, noMaster, emptyBom, notExpanded);
        given(orderLineRepository.findWithAccountAndSellerById(21L)).willReturn(Optional.of(unmapped));
        given(orderLineRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(lines);
        given(orderQueryService.rows(lines)).willReturn(List.of());
        given(orderShipmentRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of());
        given(orderLineExpander.expand(eq(lines), any())).willReturn(Map.of(
                21L, new OrderLineExpander.LineExpansion(21L, List.of(),
                        OrderLineExpander.Failure.UNMAPPED_OPTION),
                22L, new OrderLineExpander.LineExpansion(22L, List.of(),
                        OrderLineExpander.Failure.NO_MASTER_OPTION),
                23L, new OrderLineExpander.LineExpansion(23L, List.of(),
                        OrderLineExpander.Failure.EMPTY_BOM)));

        OrderDetailResponse detail = service.byOrderLine(21L);

        assertThat(detail.lineMasters()).containsExactly(
                new OrderDetailResponse.LineMaster(21L, null, null, null, List.of(), "UNMAPPED_OPTION"),
                new OrderDetailResponse.LineMaster(22L, null, null, null, List.of(), "NO_MASTER_OPTION"),
                new OrderDetailResponse.LineMaster(23L, null, null, null, List.of(), "EMPTY_BOM"),
                new OrderDetailResponse.LineMaster(24L, null, null, null, List.of(), "UNMAPPED_OPTION"));
        verify(masterProductOptionRepository, never()).findWithMasterProductByIdIn(any());
    }

    @Test
    void testOrderWithoutParcelsHasEmptyParcelList() {
        given(orderLineRepository.findWithAccountAndSellerById(11L)).willReturn(Optional.of(lineA));
        given(orderLineRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of(lineA, lineB));
        given(orderQueryService.rows(List.of(lineA, lineB))).willReturn(rows);
        given(orderShipmentRepository.findByOrder_IdOrderByIdAsc(ORDER_ID)).willReturn(List.of(first));
        // D26: no invoice issued yet = no parcel row.
        given(parcelRepository.findWithBoxByOrderShipment_IdIn(List.of(500L))).willReturn(List.of());

        OrderDetailResponse detail = service.byOrderLine(11L);

        assertThat(detail.parcels()).isEmpty();
        assertThat(detail.lines()).isSameAs(rows);
        verify(parcelItemRepository, never()).findByShipmentParcel_IdInOrderByIdAsc(any());
    }

    @Test
    void testUnknownOrderLineOrParcelIsNotFound() {
        given(orderLineRepository.findWithAccountAndSellerById(99L)).willReturn(Optional.empty());
        given(parcelRepository.findWithOrderById(98L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.byOrderLine(99L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.byParcel(98L)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static final Long MASTER_OPTION_ID = 90L;

    private static MasterProductOption masterOption() {
        MasterProduct master = MasterProduct.builder().id(80L).name("수세미 세트").build();
        return MasterProductOption.builder().id(MASTER_OPTION_ID).masterProduct(master).name("2종 묶음").build();
    }

    private static OrderLineExpander.LineExpansion linked(Long lineId, OrderLineExpander.ExpandedProduct... products) {
        return new OrderLineExpander.LineExpansion(lineId, List.of(products), null, MASTER_OPTION_ID);
    }

    private static Order order() {
        Seller seller = Seller.builder().id(5L).sellerName("셀러A").build();
        MarketplaceAccount account = MarketplaceAccount.builder().id(1L).seller(seller)
                .platform(Platform.COUPANG).accountAlias("본계정").build();
        return Order.builder().id(ORDER_ID).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId("ORD-1").orderedAt(ORDERED_AT).ordererName("김주문").receiverName("박수취").build();
    }

    private OrderLine line(Long id, String itemName) {
        return OrderLine.builder().id(id).order(order).orderShipment(first).status(OrderStatus.SHIPPED)
                .itemName(itemName).orderQty(1).cancelQty(0).holdQty(0).build();
    }

    private static ShipmentParcel parcel(Long id, OrderShipment shipment, int seq, ParcelStatus status,
                                         LocalDateTime packedAt, Package box) {
        return ShipmentParcel.builder().id(id).orderShipment(shipment).invoiceNumber("INV-" + id)
                .carrierName("롯데택배").parcelSeq(seq).status(status).packedAt(packedAt).boxPackage(box).build();
    }

    private static ShipmentParcelItem item(Long id, ShipmentParcel parcel, OrderLine line, Product product, int qty) {
        return ShipmentParcelItem.builder().id(id).shipmentParcel(parcel).orderLine(line).product(product)
                .quantity(qty).build();
    }
}
