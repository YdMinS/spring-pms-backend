package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.OrderRepository;
import com.pms.repository.OrderShipmentRepository;
import com.pms.service.coupang.OrderUpserter.UpsertCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * OrderUpserter 적재 규칙 테스트 (3개 경로 공통 진입점, FEATURE_2609_26).
 *
 * ObjectMapper 는 실제 인스턴스 — 쿠팡 응답 JSON 문자열을 그대로 먹여야 매핑을 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class OrderUpserterTest {

    private static final String BOX_ID = "700000012345";
    private static final String ORDER_ID = "300000012345";
    private static final String ITEM_ID = "800000012345";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderShipmentRepository orderShipmentRepository;
    @Mock
    private OrderLineRepository orderLineRepository;
    @Mock
    private CoupangOrderLineRepository coupangOrderLineRepository;

    @InjectMocks
    private OrderUpserter upserter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        account = MarketplaceAccountFixture.coupangStubBuilder("A00012345", null)
                .id(1L)
                .platform(Platform.COUPANG)
                .isActive(true)
                .build();
        // 저장은 인자를 그대로 돌려준다(영속화 후 참조를 흉내낸다).
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderShipmentRepository.save(any(OrderShipment.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderLineRepository.save(any(OrderLine.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void 신규박스는주문3층과쿠팡extension을만든다() {
        givenNothingExists();

        UpsertCount result = upserter.upsertBox(account, box(oneLineBox()));

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(order.capture());
        assertThat(order.getValue().getExternalOrderId()).isEqualTo(ORDER_ID);
        assertThat(order.getValue().getPlatform()).isEqualTo(Platform.COUPANG);
        assertThat(order.getValue().getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 9, 4, 1, 0, 0));

        ArgumentCaptor<OrderShipment> shipment = ArgumentCaptor.forClass(OrderShipment.class);
        verify(orderShipmentRepository).save(shipment.capture());
        assertThat(shipment.getValue().getExternalShipmentId()).isEqualTo(BOX_ID);
        assertThat(shipment.getValue().getTrackingAvailable()).isTrue();
        assertThat(shipment.getValue().getShippingFee()).isEqualByComparingTo("2500");

        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(OrderStatus.PREPARING);   // INSTRUCT → PREPARING
        assertThat(line.getValue().getOrderQty()).isEqualTo(3);
        assertThat(line.getValue().getUnitPrice()).isEqualByComparingTo("13670");   // {units,nanos}
        assertThat(line.getValue().getLineAmount()).isEqualByComparingTo("41010");
        assertThat(line.getValue().getOrderShipment()).isSameAs(shipment.getValue());

        ArgumentCaptor<CoupangOrderLine> mirror = ArgumentCaptor.forClass(CoupangOrderLine.class);
        verify(coupangOrderLineRepository).save(mirror.capture());
        assertThat(mirror.getValue().getPlatformStatus()).isEqualTo("INSTRUCT");    // 원문 보존
        assertThat(mirror.getValue().getShipmentBoxId()).isEqualTo(BOX_ID);
        assertThat(mirror.getValue().getVendorItemId()).isEqualTo(ITEM_ID);
        assertThat(mirror.getValue().getRaw()).contains(ITEM_ID);

        assertThat(result).isEqualTo(new UpsertCount(1, 0));
    }

    @Test
    void 재실행은행을늘리지않고가변필드만갱신한다() {
        OrderLine existingLine = existingLine();
        givenLineExists(existingLine);

        UpsertCount result = upserter.upsertBox(account, box(oneLineBox()));

        // 주문·배송묶음·라인 모두 기존 행을 갱신(신규 insert 없음) — 행 수는 변하지 않는다.
        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(order.capture());
        assertThat(order.getValue().getId()).isEqualTo(10L);

        ArgumentCaptor<OrderShipment> shipment = ArgumentCaptor.forClass(OrderShipment.class);
        verify(orderShipmentRepository).save(shipment.capture());
        assertThat(shipment.getValue().getId()).isEqualTo(20L);

        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getId()).isEqualTo(99L);
        assertThat(line.getValue().getStatus()).isEqualTo(OrderStatus.PREPARING);   // 갱신됨
        assertThat(line.getValue().getOrderQty()).isEqualTo(3);

        ArgumentCaptor<CoupangOrderLine> mirror = ArgumentCaptor.forClass(CoupangOrderLine.class);
        verify(coupangOrderLineRepository).save(mirror.capture());
        assertThat(mirror.getValue().getId()).isEqualTo(77L);
        assertThat(mirror.getValue().getPlatformStatus()).isEqualTo("INSTRUCT");

        assertThat(result).isEqualTo(new UpsertCount(0, 1));
    }

    @Test
    void 기존라인갱신은금액을덮지않는다() {
        // PLAN D10 — 금액은 주문 시점 스냅샷이다. 쿠팡이 다른 금액을 줘도 최초 값이 유지된다.
        givenLineExists(existingLine());

        upserter.upsertBox(account, box(oneLineBox()));

        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getUnitPrice()).isEqualByComparingTo("1000");
        assertThat(line.getValue().getLineAmount()).isEqualByComparingTo("1000");
        assertThat(line.getValue().getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(line.getValue().getPlatformDiscountAmount()).isEqualByComparingTo("0");
    }

    @Test
    void 모르는상태의박스는통째로스킵되고아무것도저장하지않는다() {
        UpsertCount result = upserter.upsertBox(account, box(boxWithStatus("SOMETHING_NEW")));

        assertThat(result).isEqualTo(UpsertCount.empty());
        verifyNoInteractions(orderRepository, orderShipmentRepository, orderLineRepository,
                coupangOrderLineRepository);
    }

    @Test
    void 박스에라인이둘이면배송묶음은1행이고라인은2행이다() {
        givenNothingExists();

        UpsertCount result = upserter.upsertBox(account, box(twoLineBox()));

        verify(orderShipmentRepository, times(1)).save(any(OrderShipment.class));
        verify(orderLineRepository, times(2)).save(any(OrderLine.class));
        verify(coupangOrderLineRepository, times(2)).save(any(CoupangOrderLine.class));
        assertThat(result).isEqualTo(new UpsertCount(2, 0));
    }

    @Test
    void 박스id가없으면배송묶음을만들지않고라인링크는null이다() {
        givenNothingExists();

        upserter.upsertBox(account, box(boxWithoutBoxId()));

        verify(orderShipmentRepository, never()).save(any(OrderShipment.class));
        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getOrderShipment()).isNull();
        // 자연키는 원문 그대로(빈 문자열) — null 로 바꾸면 MySQL 이 NULL 중복을 허용해 UNIQUE 가 풀린다.
        ArgumentCaptor<CoupangOrderLine> mirror = ArgumentCaptor.forClass(CoupangOrderLine.class);
        verify(coupangOrderLineRepository).save(mirror.capture());
        assertThat(mirror.getValue().getShipmentBoxId()).isEmpty();
    }

    @Test
    void NONE_TRACKING은SHIPPED이고추적불가로표시된다() {
        givenNothingExists();

        upserter.upsertBox(account, box(boxWithStatus("NONE_TRACKING")));

        ArgumentCaptor<OrderShipment> shipment = ArgumentCaptor.forClass(OrderShipment.class);
        verify(orderShipmentRepository).save(shipment.capture());
        assertThat(shipment.getValue().getTrackingAvailable()).isFalse();
        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void 주문자명이100자를넘으면잘라서저장() {
        givenNothingExists();

        upserter.upsertBox(account, box(boxWithOrdererName("가".repeat(120))));

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(order.capture());
        assertThat(order.getValue().getOrdererName()).hasSize(100);
        // vendorItemName 은 trim 대상이 아니다(item_name = length 500, 무가공 저장)
        ArgumentCaptor<OrderLine> line = ArgumentCaptor.forClass(OrderLine.class);
        verify(orderLineRepository).save(line.capture());
        assertThat(line.getValue().getItemName()).isEqualTo("양말A");
    }

    @Test
    void paidAt오프셋파싱실패시null() {
        givenNothingExists();

        UpsertCount result = upserter.upsertBox(account, box(boxWithPaidAt("not-a-date")));

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(order.capture());
        assertThat(order.getValue().getOrderedAt()).isNull();
        assertThat(result).isEqualTo(new UpsertCount(1, 0));                // 저장 자체는 성공
    }

    @Test
    void upsertBoxes는페이지전체를누적() {
        givenNothingExists();

        UpsertCount result = upserter.upsertBoxes(
                account, List.of(box(oneLineBox()), box(twoLineBox())));

        verify(orderLineRepository, times(3)).save(any(OrderLine.class));
        assertThat(result).isEqualTo(new UpsertCount(3, 0));
    }

    // --- fixtures ---

    private void givenNothingExists() {
        given(orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(eq(1L), any()))
                .willReturn(Optional.empty());
        lenient().when(orderShipmentRepository.findByOrder_IdAndExternalShipmentId(any(), any()))
                .thenReturn(Optional.empty());
        given(coupangOrderLineRepository
                .findByMarketplaceAccount_IdAndShipmentBoxIdAndOrderIdRawAndVendorItemId(any(), any(), any(), any()))
                .willReturn(Optional.empty());
    }

    private void givenLineExists(OrderLine line) {
        Order order = Order.builder().id(10L).marketplaceAccount(account).platform(Platform.COUPANG)
                .externalOrderId(ORDER_ID).build();
        OrderShipment shipment = OrderShipment.builder().id(20L).order(order)
                .externalShipmentId(BOX_ID).trackingAvailable(true).build();
        given(orderRepository.findByMarketplaceAccount_IdAndExternalOrderId(eq(1L), any()))
                .willReturn(Optional.of(order));
        given(orderShipmentRepository.findByOrder_IdAndExternalShipmentId(any(), any()))
                .willReturn(Optional.of(shipment));
        given(coupangOrderLineRepository
                .findByMarketplaceAccount_IdAndShipmentBoxIdAndOrderIdRawAndVendorItemId(any(), any(), any(), any()))
                .willReturn(Optional.of(CoupangOrderLine.builder()
                        .id(77L).orderLine(line).marketplaceAccount(account)
                        .shipmentBoxId(BOX_ID).orderIdRaw(ORDER_ID).vendorItemId(ITEM_ID)
                        .platformStatus("ACCEPT").raw("{}")
                        .build()));
    }

    private OrderLine existingLine() {
        return OrderLine.builder()
                .id(99L)
                .status(OrderStatus.PAID)
                .itemName("양말A")
                .orderQty(1).cancelQty(0).holdQty(0)
                .unitPrice(new BigDecimal("1000"))
                .lineAmount(new BigDecimal("1000"))
                .discountAmount(BigDecimal.ZERO)
                .platformDiscountAmount(BigDecimal.ZERO)
                .build();
    }

    private JsonNode box(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String oneLineBox() {
        return """
            {"orderId":"%s","shipmentBoxId":"%s","status":"INSTRUCT","paidAt":"2026-09-04T01:00:00+09:00",
             "shippingPrice":{"units":2500,"nanos":0,"currencyCode":"KRW"},
             "orderItems":[{"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":3,
                            "salesPrice":{"units":13670,"nanos":0,"currencyCode":"KRW"},
                            "orderPrice":{"units":41010,"nanos":0,"currencyCode":"KRW"},
                            "discountPrice":{"units":0,"nanos":0,"currencyCode":"KRW"},
                            "coupangDiscount":{"units":0,"nanos":0,"currencyCode":"KRW"}}]}
            """.formatted(ORDER_ID, BOX_ID, ITEM_ID);
    }

    private String twoLineBox() {
        return """
            {"orderId":"%s","shipmentBoxId":"%s","status":"INSTRUCT",
             "orderItems":[
               {"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":3},
               {"vendorItemId":"800000099999","vendorItemName":"양말B","shippingCount":1}
             ]}
            """.formatted(ORDER_ID, BOX_ID, ITEM_ID);
    }

    private String boxWithStatus(String status) {
        return """
            {"orderId":"%s","shipmentBoxId":"%s","status":"%s",
             "orderItems":[{"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":3}]}
            """.formatted(ORDER_ID, BOX_ID, status, ITEM_ID);
    }

    private String boxWithoutBoxId() {
        return """
            {"orderId":"%s","status":"INSTRUCT",
             "orderItems":[{"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":3}]}
            """.formatted(ORDER_ID, ITEM_ID);
    }

    private String boxWithOrdererName(String name) {
        return """
            {"orderId":"%s","shipmentBoxId":"%s","status":"INSTRUCT",
             "orderer":{"name":"%s"},
             "orderItems":[{"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":3}]}
            """.formatted(ORDER_ID, BOX_ID, name, ITEM_ID);
    }

    private String boxWithPaidAt(String paidAt) {
        return """
            {"orderId":"%s","shipmentBoxId":"%s","status":"INSTRUCT","paidAt":"%s",
             "orderItems":[{"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":3}]}
            """.formatted(ORDER_ID, BOX_ID, paidAt, ITEM_ID);
    }
}
