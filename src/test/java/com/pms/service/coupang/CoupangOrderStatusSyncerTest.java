package com.pms.service.coupang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderItemRepository;
import com.pms.service.coupang.CoupangOrderStatusSyncer.StatusSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * (이 검증들은 로직이 CoupangOrderSyncServiceImpl 에서 이 클래스로 옮겨오며 함께 이동했다.)
 * CoupangApiClient 는 @Mock 으로 캔드 JSON 을 반환하고, ObjectMapper 는 실제 인스턴스를 쓴다.
 * OrderItemRepository 는 in-memory 맵으로 find/save 의미를 흉내내 2회 동기화의 멱등성을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CoupangOrderStatusSyncerTest {

    @Mock
    private CoupangApiClient coupangApiClient;

    // CoupangApiClient 외 나머지는 실제 인스턴스/in-memory 스텁으로 직접 조립
    private OrderItemRepository orderItemRepository;
    private CoupangOrderStatusSyncer syncer;

    private MarketplaceAccount account;

    /** external 4키 → 저장된 OrderItem (실 DB 의 UNIQUE 제약을 흉내). */
    private Map<String, OrderItem> store;

    @BeforeEach
    void setUp() {
        account = MarketplaceAccount.builder()
                .id(1L)
                .platform("COUPANG")
                .vendorId("V0001")
                .accessKey("ak")
                .secretKey("sk")
                .isActive(true)
                .build();

        store = new HashMap<>();
        orderItemRepository = inMemoryRepository(store);

        CoupangProperties props = new CoupangProperties();
        props.setOrdersheetsPath("/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets");
        props.setSyncDays(5);

        syncer = new CoupangOrderStatusSyncer(
                coupangApiClient, orderItemRepository, props, new ObjectMapper());
    }

    @Test
    void syncStatus_insertsNewOrderItems() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(twoBoxesThreeLines());

        StatusSyncResult result = syncer.syncStatus(account, CoupangOrderStatus.ACCEPT);

        assertThat(store).hasSize(3);
        assertThat(result.newCount()).isEqualTo(3);
        assertThat(result.updatedCount()).isZero();

        // 조회 쿼리는 요청한 상태 하나만 담는다 (vendorId 치환 경로 포함).
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).get(pathCaptor.capture(), queryCaptor.capture(), any());
        assertThat(pathCaptor.getValue()).contains("V0001");
        assertThat(queryCaptor.getValue()).contains("status=ACCEPT");

        // paidAt: 오프셋 포함 ISO-8601 → KST 로컬시각 (2026-06-15T01:00:00+09:00 == 2026-06-15T01:00)
        OrderItem line = store.get(key(1L, "B1", "O1", "I1"));
        assertThat(line.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 6, 15, 1, 0, 0));
        assertThat(line.getItemName()).isEqualTo("양말A");
        assertThat(line.getOrderCount()).isEqualTo(3);
        assertThat(line.getHoldCount()).isEqualTo(1);
    }

    @Test
    void syncStatus_isIdempotent_noDuplicateOnSecondRun() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(twoBoxesThreeLines());

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT);                 // 1회차: 3 insert
        StatusSyncResult second = syncer.syncStatus(account, CoupangOrderStatus.ACCEPT); // 2회차: 모두 update

        assertThat(store).hasSize(3);                 // 중복 안 쌓임 ★
        assertThat(second.newCount()).isZero();
        assertThat(second.updatedCount()).isEqualTo(3);
    }

    @Test
    void syncStatus_updatesMutableFields_onExisting() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(twoBoxesThreeLines(), twoBoxesThreeLinesCancelChanged());

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT);   // 1회차: cancelCount=0
        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT);   // 2회차: line(order=O1, box=B1, item=I1) cancelCount=2

        OrderItem changed = store.get(key(1L, "B1", "O1", "I1"));
        assertThat(changed.getCancelCount()).isEqualTo(2);
        assertThat(changed.getHoldCount()).isEqualTo(1);
        // 다른 줄은 그대로
        assertThat(store.get(key(1L, "B1", "O1", "I2")).getCancelCount()).isZero();
    }

    @Test
    void syncStatus_paginates_untilNextTokenBlank() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(pageWithToken("t"), pageWithToken(""));

        StatusSyncResult result = syncer.syncStatus(account, CoupangOrderStatus.ACCEPT);

        verify(coupangApiClient, times(2)).get(anyString(), anyString(), any());
        assertThat(result.pages()).isEqualTo(2);
        assertThat(store).hasSize(2);                 // 페이지당 1줄 × 2
        assertThat(result.newCount()).isEqualTo(2);
    }

    @Test
    void purchasableQty_subtractsCancelAndHold() {
        assertThat(orderItem(10, 2, 1).purchasableQty()).isEqualTo(7);
        assertThat(orderItem(5, 5, 0).purchasableQty()).isZero();   // 음수면 0
    }

    @Test
    void syncStatus_storesCustomerNames_fromBoxLevel() {
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(twoBoxesThreeLines());

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT);

        // 박스 레벨 값이 그 박스의 모든 라인에 복제된다 (B1 = I1, I2)
        assertThat(store.get(key(1L, "B1", "O1", "I1")).getOrdererName()).isEqualTo("홍길동");
        assertThat(store.get(key(1L, "B1", "O1", "I1")).getReceiverName()).isEqualTo("김철수");
        assertThat(store.get(key(1L, "B1", "O1", "I2")).getReceiverName()).isEqualTo("김철수");
        // 이름 필드가 없는 박스는 null 로 남는다 (D7)
        assertThat(store.get(key(1L, "B2", "O2", "I3")).getOrdererName()).isNull();
        assertThat(store.get(key(1L, "B2", "O2", "I3")).getReceiverName()).isNull();
    }

    @Test
    void syncStatus_backfillsCustomerNames_onExistingRow() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn(singleLine(false), singleLine(true));

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT);          // 1회차: 이름 없는 응답
        assertThat(store.get(key(1L, "B1", "O1", "I1")).getReceiverName()).isNull();

        syncer.syncStatus(account, CoupangOrderStatus.ACCEPT);          // 2회차: 이름 있는 응답

        assertThat(store).hasSize(1);                                   // 행이 늘지 않는다(멱등 유지)
        assertThat(store.get(key(1L, "B1", "O1", "I1")).getOrdererName()).isEqualTo("홍길동");
        assertThat(store.get(key(1L, "B1", "O1", "I1")).getReceiverName()).isEqualTo("김철수");
    }

    // --- helpers ---

    private static OrderItem orderItem(int order, int cancel, int hold) {
        return OrderItem.builder().orderCount(order).cancelCount(cancel).holdCount(hold).build();
    }

    private static String key(Long accountId, String box, String order, String item) {
        return accountId + "|" + box + "|" + order + "|" + item;
    }

    /** save/find 를 4키 맵으로 처리하는 in-memory OrderItemRepository (Mockito Answer 기반). */
    private OrderItemRepository inMemoryRepository(Map<String, OrderItem> store) {
        // lenient: 순수 단위 테스트(purchasableQty)는 이 스텁을 안 써서 strict stubbing 위반을 피한다.
        OrderItemRepository repo = org.mockito.Mockito.mock(OrderItemRepository.class,
                org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        AtomicLong seq = new AtomicLong(0);

        given(repo.save(any(OrderItem.class))).willAnswer(inv -> {
            OrderItem oi = inv.getArgument(0);
            OrderItem persisted = oi.getId() == null
                    ? oi.toBuilder().id(seq.incrementAndGet()).build()
                    : oi;
            store.put(key(persisted.getMarketplaceAccount().getId(),
                    persisted.getExternalBoxId(), persisted.getExternalOrderId(),
                    persisted.getExternalItemId()), persisted);
            return persisted;
        });

        given(repo.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
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
