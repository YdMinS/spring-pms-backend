package com.pms.service.coupang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
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
import com.pms.service.coupang.CoupangOrderStatusSyncer.StatusSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CoupangOrderStatusSyncer 멱등성·매핑·페이징 테스트.
 *
 * CoupangApiClient 는 @Mock 으로 캔드 JSON 을 반환하고, ObjectMapper 는 실제 인스턴스를 쓴다.
 * 리포지토리 4개는 in-memory 맵으로 find/save 의미를 흉내내 2회 동기화의 멱등성을 검증한다
 * (FEATURE_2609_26 로 주문 3층 + 쿠팡 extension 구조가 되며 함께 이동했다).
 */
@ExtendWith(MockitoExtension.class)
class CoupangOrderStatusSyncerTest {

    @Mock
    private CoupangApiClient coupangApiClient;

    private CoupangOrderStatusSyncer syncer;

    private MarketplaceAccount account;

    /** 쿠팡 4키 → 저장된 extension 행 (실 DB 의 UNIQUE 제약을 흉내). */
    private Map<String, CoupangOrderLine> store;
    /** order_line.id → 최신 라인 (갱신은 새 인스턴스를 저장하므로 id 로 되찾는다). */
    private Map<Long, OrderLine> lines;
    /** (계정|주문번호) → 주문 헤더. */
    private Map<String, Order> orders;
    /** (주문 id|박스 id) → 배송 묶음. */
    private Map<String, OrderShipment> shipments;

    /** 조회 창은 호출자가 만든다(D6) — 이 클래스는 받은 창을 그대로 쿼리에 싣는다. */
    private static final SyncWindow WINDOW =
            new SyncWindow(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 15));

    @BeforeEach
    void setUp() {
        account = MarketplaceAccountFixture.coupangStubBuilder("V0001", null)
                .id(1L)
                .platform(Platform.COUPANG)
                .isActive(true)
                .build();

        store = new HashMap<>();
        lines = new HashMap<>();
        orders = new HashMap<>();
        shipments = new HashMap<>();

        CoupangProperties props = new CoupangProperties();
        props.setOrdersheetsPath("/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets");
        props.setSyncDays(5);

        // 목이 아니라 진짜 upserter 를 넣는다(그래야 저장 동작이 그대로 검증된다).
        syncer = new CoupangOrderStatusSyncer(
                coupangApiClient,
                new OrderUpserter(orderRepository(), shipmentRepository(), lineRepository(), mirrorRepository()),
                props, new ObjectMapper());
    }

    @Test
    void syncStatus_insertsNewOrderLines() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(twoBoxesThreeLines());

        StatusSyncResult result = syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW);

        assertThat(store).hasSize(3);
        assertThat(orders).hasSize(2);            // 주문 2건
        assertThat(shipments).hasSize(2);         // 박스 2개
        assertThat(result.newCount()).isEqualTo(3);
        assertThat(result.updatedCount()).isZero();

        // 조회 쿼리는 요청한 상태 하나만 담는다 (vendorId 치환 경로 포함).
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).get(pathCaptor.capture(), queryCaptor.capture(), any());
        assertThat(pathCaptor.getValue()).contains("V0001");
        assertThat(queryCaptor.getValue()).contains("status=ACCEPT")
                // 창은 파라미터에서 온다(D6) — 클래스가 오늘 날짜를 계산하지 않는다.
                .contains("createdAtFrom=2026-06-10%2B09:00")
                .contains("createdAtTo=2026-06-15%2B09:00");

        // paidAt: 오프셋 포함 ISO-8601 → KST 로컬시각, 주문 헤더에 저장된다.
        assertThat(order("O1").getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 6, 15, 1, 0, 0));

        OrderLine line = line(key(1L, "B1", "O1", "I1"));
        assertThat(line.getItemName()).isEqualTo("양말A");
        assertThat(line.getOrderQty()).isEqualTo(3);
        assertThat(line.getHoldQty()).isEqualTo(1);
        assertThat(line.getStatus()).isEqualTo(OrderStatus.PAID);       // ACCEPT → PAID
        assertThat(store.get(key(1L, "B1", "O1", "I1")).getPlatformStatus()).isEqualTo("ACCEPT");
    }

    @Test
    void syncStatus_isIdempotent_noDuplicateOnSecondRun() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(twoBoxesThreeLines());

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW);                 // 1회차: 3 insert
        StatusSyncResult second = syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW); // 2회차: 모두 update

        assertThat(store).hasSize(3);                 // 중복 안 쌓임 ★
        assertThat(lines).hasSize(3);
        assertThat(orders).hasSize(2);
        assertThat(shipments).hasSize(2);
        assertThat(second.newCount()).isZero();
        assertThat(second.updatedCount()).isEqualTo(3);
    }

    @Test
    void syncStatus_updatesMutableFields_onExisting() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(twoBoxesThreeLines(), twoBoxesThreeLinesCancelChanged());

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW);   // 1회차: cancelCount=0
        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW);   // 2회차: line(order=O1, box=B1, item=I1) cancelCount=2

        OrderLine changed = line(key(1L, "B1", "O1", "I1"));
        assertThat(changed.getCancelQty()).isEqualTo(2);
        assertThat(changed.getHoldQty()).isEqualTo(1);
        // 다른 줄은 그대로
        assertThat(line(key(1L, "B1", "O1", "I2")).getCancelQty()).isZero();
    }

    @Test
    void syncStatus_paginates_untilNextTokenBlank() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(pageWithToken("t"), pageWithToken(""));

        StatusSyncResult result = syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW);

        verify(coupangApiClient, times(2)).get(anyString(), anyString(), any());
        assertThat(result.pages()).isEqualTo(2);
        assertThat(store).hasSize(2);                 // 페이지당 1줄 × 2
        assertThat(result.newCount()).isEqualTo(2);
    }

    @Test
    void purchasableQty_subtractsCancelAndHold() {
        assertThat(orderLine(10, 2, 1).purchasableQty()).isEqualTo(7);
        assertThat(orderLine(5, 5, 0).purchasableQty()).isZero();   // 음수면 0
    }

    @Test
    void syncStatus_storesCustomerNames_onOrderHeader() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(twoBoxesThreeLines());

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW);

        // 박스 레벨 값은 주문 헤더에 1번만 저장된다(라인 복제 없음).
        assertThat(order("O1").getOrdererName()).isEqualTo("홍길동");
        assertThat(order("O1").getReceiverName()).isEqualTo("김철수");
        // 이름 필드가 없는 박스는 null 로 남는다 (2609_13 D7)
        assertThat(order("O2").getOrdererName()).isNull();
        assertThat(order("O2").getReceiverName()).isNull();
    }

    @Test
    void syncStatus_backfillsCustomerNames_onExistingRow() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(singleLine(false), singleLine(true));

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW);          // 1회차: 이름 없는 응답
        assertThat(order("O1").getReceiverName()).isNull();

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT, WINDOW);          // 2회차: 이름 있는 응답

        assertThat(store).hasSize(1);                                   // 행이 늘지 않는다(멱등 유지)
        assertThat(orders).hasSize(1);
        assertThat(order("O1").getOrdererName()).isEqualTo("홍길동");
        assertThat(order("O1").getReceiverName()).isEqualTo("김철수");
    }

    // --- helpers ---

    private static OrderLine orderLine(int order, int cancel, int hold) {
        return OrderLine.builder().orderQty(order).cancelQty(cancel).holdQty(hold).build();
    }

    private static String key(Long accountId, String box, String order, String item) {
        return accountId + "|" + box + "|" + order + "|" + item;
    }

    private OrderLine line(String storeKey) {
        return lines.get(store.get(storeKey).getOrderLine().getId());
    }

    private Order order(String externalOrderId) {
        return orders.get("1|" + externalOrderId);
    }

    private OrderRepository orderRepository() {
        // lenient: 순수 단위 테스트(purchasableQty)는 이 스텁을 안 써서 strict stubbing 위반을 피한다.
        OrderRepository repo = Mockito.mock(OrderRepository.class,
                Mockito.withSettings().strictness(Strictness.LENIENT));
        AtomicLong seq = new AtomicLong(0);
        given(repo.save(any(Order.class))).willAnswer(inv -> {
            Order o = inv.getArgument(0);
            Order persisted = o.getId() == null ? o.toBuilder().id(seq.incrementAndGet()).build() : o;
            orders.put(persisted.getMarketplaceAccount().getId() + "|" + persisted.getExternalOrderId(), persisted);
            return persisted;
        });
        given(repo.findByMarketplaceAccount_IdAndExternalOrderId(any(), anyString()))
                .willAnswer(inv -> Optional.ofNullable(orders.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        return repo;
    }

    private OrderShipmentRepository shipmentRepository() {
        OrderShipmentRepository repo = Mockito.mock(OrderShipmentRepository.class,
                Mockito.withSettings().strictness(Strictness.LENIENT));
        AtomicLong seq = new AtomicLong(0);
        given(repo.save(any(OrderShipment.class))).willAnswer(inv -> {
            OrderShipment s = inv.getArgument(0);
            OrderShipment persisted = s.getId() == null ? s.toBuilder().id(seq.incrementAndGet()).build() : s;
            shipments.put(persisted.getOrder().getId() + "|" + persisted.getExternalShipmentId(), persisted);
            return persisted;
        });
        given(repo.findByOrder_IdAndExternalShipmentId(any(), anyString()))
                .willAnswer(inv -> Optional.ofNullable(shipments.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        return repo;
    }

    private OrderLineRepository lineRepository() {
        OrderLineRepository repo = Mockito.mock(OrderLineRepository.class,
                Mockito.withSettings().strictness(Strictness.LENIENT));
        AtomicLong seq = new AtomicLong(0);
        given(repo.save(any(OrderLine.class))).willAnswer(inv -> {
            OrderLine l = inv.getArgument(0);
            OrderLine persisted = l.getId() == null ? l.toBuilder().id(seq.incrementAndGet()).build() : l;
            lines.put(persisted.getId(), persisted);
            return persisted;
        });
        return repo;
    }

    private CoupangOrderLineRepository mirrorRepository() {
        CoupangOrderLineRepository repo = Mockito.mock(CoupangOrderLineRepository.class,
                Mockito.withSettings().strictness(Strictness.LENIENT));
        AtomicLong seq = new AtomicLong(0);
        given(repo.save(any(CoupangOrderLine.class))).willAnswer(inv -> {
            CoupangOrderLine m = inv.getArgument(0);
            CoupangOrderLine persisted = m.getId() == null ? m.toBuilder().id(seq.incrementAndGet()).build() : m;
            store.put(key(persisted.getMarketplaceAccount().getId(), persisted.getShipmentBoxId(),
                    persisted.getOrderIdRaw(), persisted.getVendorItemId()), persisted);
            return persisted;
        });
        given(repo.findByMarketplaceAccount_IdAndShipmentBoxIdAndOrderIdRawAndVendorItemId(
                any(), anyString(), anyString(), anyString()))
                .willAnswer(inv -> Optional.ofNullable(store.get(
                        key(inv.getArgument(0), inv.getArgument(1),
                                inv.getArgument(2), inv.getArgument(3)))));
        return repo;
    }

    /** B1(O1)의 I1 한 줄만. withNames=true 면 박스에 orderer/receiver 를 넣는다(백필 검증용). */
    private String singleLine(boolean withNames) {
        String names = withNames
                ? "\"orderer\":{\"name\":\"홍길동\"},\"receiver\":{\"name\":\"김철수\"},"
                : "";
        return """
            {"data":[
              {"orderId":"O1","shipmentBoxId":"B1","status":"ACCEPT",%s
               "orderItems":[{"vendorItemId":"I1","vendorItemName":"양말A","shippingCount":3}]}
            ],"nextToken":""}
            """.formatted(names);
    }

    // --- canned JSON ---

    // box B1(order O1): 2 lines I1,I2 + 고객명 있음 / box B2(order O2): 1 line I3, 고객명 없음
    private String twoBoxesThreeLines() {
        return """
            {"data":[
              {"orderId":"O1","shipmentBoxId":"B1","status":"ACCEPT","paidAt":"2026-06-15T01:00:00+09:00",
               "orderer":{"name":"홍길동"},"receiver":{"name":"김철수"},
               "orderItems":[
                 {"vendorItemId":"I1","vendorItemName":"양말A","shippingCount":3,"holdCountForCancel":1},
                 {"vendorItemId":"I2","vendorItemName":"양말B","shippingCount":2}
               ]},
              {"orderId":"O2","shipmentBoxId":"B2","status":"ACCEPT","paidAt":"2026-06-15T11:00:00+09:00",
               "orderItems":[
                 {"vendorItemId":"I3","vendorItemName":"양말C","shippingCount":5}
               ]}
            ],"nextToken":""}
            """;
    }

    // 동일 구조에서 I1 의 cancelCount 만 2로 변경
    private String twoBoxesThreeLinesCancelChanged() {
        return """
            {"data":[
              {"orderId":"O1","shipmentBoxId":"B1","status":"ACCEPT","paidAt":"2026-06-15T01:00:00+09:00",
               "orderItems":[
                 {"vendorItemId":"I1","vendorItemName":"양말A","shippingCount":3,"cancelCount":2,"holdCountForCancel":1},
                 {"vendorItemId":"I2","vendorItemName":"양말B","shippingCount":2}
               ]},
              {"orderId":"O2","shipmentBoxId":"B2","status":"ACCEPT","paidAt":"2026-06-15T11:00:00+09:00",
               "orderItems":[
                 {"vendorItemId":"I3","vendorItemName":"양말C","shippingCount":5}
               ]}
            ],"nextToken":""}
            """;
    }

    // 페이지마다 고유한 1줄 (token 으로 구분)
    private String pageWithToken(String token) {
        String suffix = token.isBlank() ? "P2" : "P1";
        return """
            {"data":[
              {"orderId":"O-%s","shipmentBoxId":"B-%s","status":"ACCEPT",
               "orderItems":[{"vendorItemId":"I-%s","shippingCount":1}]}
            ],"nextToken":"%s"}
            """.formatted(suffix, suffix, suffix, token);
    }
}
