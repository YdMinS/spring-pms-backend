package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.request.OrderRefreshRequest;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.OrderLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * OrderRefreshServiceImpl 주문번호 dedupe·적재·4분류 집계·상한 테스트 (FEATURE_2609_50).
 *
 * CoupangApiClient·CoupangProperties·OrderLineRepository·OrderUpserter 는 @Mock,
 * ObjectMapper 는 실제 인스턴스(응답 봉투를 그대로 파싱해 검증하기 위해).
 *
 * ⚠️ 락 관련 테스트는 없다 — 서비스가 AccountSyncLock 을 주입받지 않으므로(D4) 목을 만들어
 *    verifyNoInteractions 를 걸어도 항상 통과하는 빈 테스트가 된다.
 */
@ExtendWith(MockitoExtension.class)
class OrderRefreshServiceImplTest {

    private static final String BY_ORDER_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/{orderId}/ordersheets";

    @Mock
    private CoupangApiClient coupangApiClient;
    @Mock
    private CoupangProperties coupangProperties;
    @Mock
    private OrderLineRepository orderLineRepository;
    @Mock
    private OrderUpserter orderUpserter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderRefreshServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderRefreshServiceImpl(
                coupangApiClient, coupangProperties, orderLineRepository, orderUpserter, objectMapper);
    }

    @Test
    void refreshDedupesLinesOfSameOrderIntoOneCall() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        // 옵션 3줄 = 같은 주문번호
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(1L, account, "4000019469460"),
                line(2L, account, "4000019469460"),
                line(3L, account, "4000019469460")));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(BY_ORDER_PATH);
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(oneBox("4000019469460"));

        OrderRefreshResult result = service.refresh(request(1L, 2L, 3L));

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(1)).get(path.capture(), eq(""), eq(account));
        assertThat(path.getValue())
                .contains("/vendors/A001/4000019469460/ordersheets")
                .doesNotContain("{vendorId}").doesNotContain("{orderId}");
        assertThat(result.requestedOrders()).isEqualTo(1);
        assertThat(result.refreshed()).isEqualTo(1);
    }

    @Test
    void refreshUpsertsReturnedBoxes() throws Exception {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(1L, account, "4000000001")));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(BY_ORDER_PATH);
        String response = oneBox("4000000001");
        given(coupangApiClient.get(anyString(), anyString(), any())).willReturn(response);

        OrderRefreshResult result = service.refresh(request(1L));

        // 저장은 upsertBoxes(복수형) 1회 — 주문 1건이 커밋 단위다
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<JsonNode>> boxes = ArgumentCaptor.forClass(Iterable.class);
        verify(orderUpserter, times(1)).upsertBoxes(eq(account), boxes.capture());
        assertThat(boxes.getValue()).containsExactly(objectMapper.readTree(response).path("data").get(0));
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.empty()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void refreshReportsEmptyResponseAsEmptyNotFailed() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(1L, account, "4000000002")));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(BY_ORDER_PATH);
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn("{\"code\":200,\"data\":[]}");

        OrderRefreshResult result = service.refresh(request(1L));

        assertThat(result.empty()).containsExactly("4000000002");
        assertThat(result.failed()).isEmpty();
        assertThat(result.refreshed()).isZero();
        verify(orderUpserter, never()).upsertBoxes(any(), any());
    }

    @Test
    void refreshIsolatesFailurePerOrder() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(1L, account, "4000000011"),
                line(2L, account, "4000000012"),
                line(3L, account, "4000000013")));
        given(coupangProperties.getOrdersheetByOrderPath()).willReturn(BY_ORDER_PATH);
        given(coupangApiClient.get(contains("4000000011"), anyString(), any())).willReturn(oneBox("4000000011"));
        given(coupangApiClient.get(contains("4000000012"), anyString(), any()))
                .willThrow(new RestClientException("500 gateway"));
        given(coupangApiClient.get(contains("4000000013"), anyString(), any())).willReturn(oneBox("4000000013"));

        OrderRefreshResult result = service.refresh(request(1L, 2L, 3L));

        assertThat(result.requestedOrders()).isEqualTo(3);
        assertThat(result.refreshed()).isEqualTo(2);
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).externalOrderId()).isEqualTo("4000000012");
        assertThat(result.failed().get(0).reason()).contains("500 gateway");
    }

    @Test
    void refreshMarksNonCoupangAsUnsupported() {
        // 판정은 계정 기준 — orders.platform 이 아니다.
        MarketplaceAccount naver = account(2L, Platform.NAVER, "N001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(1L, naver, "4000000003"),
                line(2L, naver, "4000000003")));

        OrderRefreshResult result = service.refresh(request(1L, 2L));

        verify(coupangApiClient, never()).get(anyString(), anyString(), any());
        // 주문번호 단위 = 라인 2개라도 1건만 보고한다
        assertThat(result.unsupported()).containsExactly("4000000003");
        assertThat(result.requestedOrders()).isZero();
    }

    @Test
    void refreshRejectsOverLimitByOrderCount() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            lines.add(line(i + 1L, account, "500000000" + i));
        }
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(lines);

        assertThatThrownBy(() -> service.refresh(request(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
        verify(coupangApiClient, never()).get(anyString(), anyString(), any());
    }

    @Test
    void refreshThrowsWhenNoLineFound() {
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of());

        assertThatThrownBy(() -> service.refresh(request(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("주문 라인을 찾을 수 없습니다");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private OrderRefreshRequest request(Long... ids) {
        return new OrderRefreshRequest(List.of(ids));
    }

    private MarketplaceAccount account(Long id, Platform platform, String vendorId) {
        Seller seller = Seller.builder().id(id).sellerName("셀러" + id)
                .businessRegistration("123-45-6789" + id).build();
        return MarketplaceAccountFixture.coupangStubBuilder(vendorId, null)
                .id(id).seller(seller).platform(platform)
                .isActive(true).build();
    }

    private OrderLine line(Long id, MarketplaceAccount account, String orderId) {
        Order order = Order.builder()
                .marketplaceAccount(account).platform(account.getPlatform())
                .externalOrderId(orderId).build();
        return OrderLine.builder()
                .id(id).order(order)
                .orderQty(1).cancelQty(0).holdQty(0).status(OrderStatus.PREPARING).build();
    }

    /** 박스 1개짜리 정상 봉투. */
    private String oneBox(String orderId) {
        return "{\"code\":200,\"data\":[{\"orderId\":" + orderId + ",\"shipmentBoxId\":123,"
                + "\"status\":\"DEPARTURE\",\"orderItems\":[]}]}";
    }
}
