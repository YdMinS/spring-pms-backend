package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.OrderShipment;
import com.pms.domain.OrderStatus;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.request.OrderAcknowledgeRequest;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.OrderLineRepository;
import com.pms.service.coupang.CoupangApiClient;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * OrderAcknowledgeServiceImpl 전개·박스 dedupe·청크·집계·write-back 테스트.
 *
 * CoupangApiClient·OrderLineRepository·CoupangProperties 는 @Mock,
 * ObjectMapper 는 실제 인스턴스(요청 바디를 문자열로 캡처해 검증하기 위해).
 */
@ExtendWith(MockitoExtension.class)
class OrderAcknowledgeServiceImplTest {

    private static final String ACK_PATH =
            "/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets/acknowledgement";

    @Mock
    private CoupangApiClient coupangApiClient;
    @Mock
    private CoupangProperties coupangProperties;
    @Mock
    private OrderLineRepository orderLineRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderAcknowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderAcknowledgeServiceImpl(
                coupangApiClient, coupangProperties, orderLineRepository, objectMapper);
    }

    @Test
    void testAcknowledgeSendsDistinctBoxIds() throws Exception {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        // 옵션 3줄 = 같은 박스
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(account, "302012345678", "4000019469460", "1", OrderStatus.PAID),
                line(account, "302012345678", "4000019469460", "2", OrderStatus.PAID),
                line(account, "302012345678", "4000019469460", "3", OrderStatus.PAID)));
        given(coupangProperties.getAcknowledgementPath()).willReturn(ACK_PATH);
        given(coupangApiClient.put(anyString(), anyString(), any())).willReturn(responseAllSuccess("302012345678"));

        OrderAcknowledgeResult result = service.acknowledge(request(1L, 2L, 3L));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(1)).put(anyString(), body.capture(), eq(account));
        JsonNode boxIds = objectMapper.readTree(body.getValue()).get("shipmentBoxIds");
        assertThat(boxIds).hasSize(1);
        assertThat(boxIds.get(0).asLong()).isEqualTo(302012345678L);
        assertThat(result.targetBoxes()).isEqualTo(1);
        assertThat(result.requestedLines()).isEqualTo(3);
    }

    @Test
    void testAcknowledgeSkipsNonAcceptStatus() throws Exception {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(account, "111", "4000000001", "1", OrderStatus.PREPARING),
                line(account, "222", "4000000002", "1", OrderStatus.PAID)));
        given(coupangProperties.getAcknowledgementPath()).willReturn(ACK_PATH);
        given(coupangApiClient.put(anyString(), anyString(), any())).willReturn(responseAllSuccess("222"));

        OrderAcknowledgeResult result = service.acknowledge(request(1L, 2L));

        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).orderId()).isEqualTo("4000000001");
        // 스킵 상태도 중립 값이다(2609_26 D4).
        assertThat(result.skipped().get(0).status()).isEqualTo("PREPARING");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).put(anyString(), body.capture(), any());
        JsonNode boxIds = objectMapper.readTree(body.getValue()).get("shipmentBoxIds");
        assertThat(boxIds).hasSize(1);
        assertThat(boxIds.get(0).asLong()).isEqualTo(222L);
    }

    @Test
    void testAcknowledgeReportsNonCoupangAsUnsupported() {
        // 판정은 계정 기준 — orders.platform 이 아니다.
        MarketplaceAccount naver = account(2L, Platform.NAVER, "N001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(naver, "333", "4000000003", "1", OrderStatus.PAID)));

        OrderAcknowledgeResult result = service.acknowledge(request(1L));

        verify(coupangApiClient, never()).put(anyString(), anyString(), any());
        assertThat(result.unsupported()).containsExactly("4000000003");
        assertThat(result.targetBoxes()).isZero();
    }

    @Test
    void testAcknowledgeReportsMissingBoxIdAsUnsupported() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(account, null, "4000000004", "1", OrderStatus.PAID)));

        OrderAcknowledgeResult result = service.acknowledge(request(1L));

        verify(coupangApiClient, never()).put(anyString(), anyString(), any());
        assertThat(result.unsupported()).containsExactly("4000000004");
    }

    @Test
    void testAcknowledgeWritesBackInstructOnSuccess() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(account, "444", "4000000005", "1", OrderStatus.PAID),
                line(account, "444", "4000000005", "2", OrderStatus.PAID)));
        given(coupangProperties.getAcknowledgementPath()).willReturn(ACK_PATH);
        given(coupangApiClient.put(anyString(), anyString(), any())).willReturn(responseAllSuccess("444"));

        service.acknowledge(request(1L, 2L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderLineRepository).saveAll(captor.capture());
        // 그 박스의 라인 전부가 INSTRUCT
        assertThat(captor.getValue()).hasSize(2)
                .allMatch(l -> l.getStatus() == OrderStatus.PREPARING);
    }

    @Test
    void testAcknowledgeDoesNotWriteBackFailedBox() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(account, "555", "4000000006", "1", OrderStatus.PAID)));
        given(coupangProperties.getAcknowledgementPath()).willReturn(ACK_PATH);
        given(coupangApiClient.put(anyString(), anyString(), any())).willReturn(
                "{\"code\":200,\"data\":{\"responseList\":[{\"shipmentBoxId\":\"555\",\"succeed\":false,"
                        + "\"resultCode\":\"ALREADY_INSTRUCT\",\"resultMessage\":\"이미 상품준비중입니다\"}]}}");

        OrderAcknowledgeResult result = service.acknowledge(request(1L));

        verify(orderLineRepository, never()).saveAll(anyList());
        assertThat(result.succeeded()).isZero();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).resultCode()).isEqualTo("ALREADY_INSTRUCT");
        assertThat(result.failed().get(0).message()).isEqualTo("이미 상품준비중입니다");
    }

    @Test
    void testAcknowledgeIsolatesAccountFailure() {
        MarketplaceAccount a = account(1L, Platform.COUPANG, "A001");
        MarketplaceAccount b = account(2L, Platform.COUPANG, "B002");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(a, "666", "4000000007", "1", OrderStatus.PAID),
                line(b, "777", "4000000008", "1", OrderStatus.PAID)));
        given(coupangProperties.getAcknowledgementPath()).willReturn(ACK_PATH);
        given(coupangApiClient.put(anyString(), anyString(), eq(a))).willReturn(responseAllSuccess("666"));
        given(coupangApiClient.put(anyString(), anyString(), eq(b)))
                .willThrow(new RestClientException("500 gateway"));

        OrderAcknowledgeResult result = service.acknowledge(request(1L, 2L));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).shipmentBoxId()).isEqualTo("777");
        assertThat(result.failed().get(0).resultCode()).isEqualTo("ERROR");
    }

    @Test
    void testAcknowledgeChunksOver50Boxes() throws Exception {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            lines.add(line(account, String.valueOf(1000 + i), "40000001" + i, "1", OrderStatus.PAID));
        }
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(lines);
        given(coupangProperties.getAcknowledgementPath()).willReturn(ACK_PATH);
        // 첫 청크 실패 → 둘째 청크는 계속 보낸다
        given(coupangApiClient.put(anyString(), anyString(), any()))
                .willThrow(new RestClientException("boom"))
                .willReturn(responseAllSuccess("1050"));

        OrderAcknowledgeResult result = service.acknowledge(request(1L));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(2)).put(anyString(), body.capture(), any());
        assertThat(objectMapper.readTree(body.getAllValues().get(0)).get("shipmentBoxIds")).hasSize(50);
        assertThat(objectMapper.readTree(body.getAllValues().get(1)).get("shipmentBoxIds")).hasSize(1);
        assertThat(result.targetBoxes()).isEqualTo(51);
        assertThat(result.failed()).hasSize(50);
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void testAcknowledgeSucceedsWhenWriteBackFails() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001");
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(account, "888", "4000000009", "1", OrderStatus.PAID)));
        given(coupangProperties.getAcknowledgementPath()).willReturn(ACK_PATH);
        given(coupangApiClient.put(anyString(), anyString(), any())).willReturn(responseAllSuccess("888"));
        willThrow(new RuntimeException("db down")).given(orderLineRepository).saveAll(anyList());

        OrderAcknowledgeResult result = service.acknowledge(request(1L));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void testAcknowledgeThrowsWhenNoLineFound() {
        given(orderLineRepository.findWithAccountByIdIn(any())).willReturn(List.of());

        assertThatThrownBy(() -> service.acknowledge(request(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("주문 라인을 찾을 수 없습니다");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private OrderAcknowledgeRequest request(Long... ids) {
        return new OrderAcknowledgeRequest(List.of(ids));
    }

    private MarketplaceAccount account(Long id, Platform platform, String vendorId) {
        Seller seller = Seller.builder().id(id).sellerName("셀러" + id).businessRegistration("123-45-6789" + id).build();
        return MarketplaceAccountFixture.coupangStubBuilder(vendorId, null)
                .id(id).seller(seller).platform(platform)
                .isActive(true).build();
    }

    private OrderLine line(MarketplaceAccount account, String boxId, String orderId, String itemId,
                           OrderStatus status) {
        Order order = Order.builder()
                .marketplaceAccount(account).platform(account.getPlatform())
                .externalOrderId(orderId).build();
        // 박스 id 는 배송 묶음에 있다 — 없으면 묶음 행 자체가 없다(2609_26 D2).
        OrderShipment shipment = (boxId == null) ? null
                : OrderShipment.builder().order(order).externalShipmentId(boxId).build();
        return OrderLine.builder()
                .id(Long.parseLong(itemId)).order(order).orderShipment(shipment)
                .orderQty(1).cancelQty(0).holdQty(0).status(status).build();
    }

    private String responseAllSuccess(String... boxIds) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < boxIds.length; i++) {
            if (i > 0) list.append(",");
            list.append("{\"shipmentBoxId\":\"").append(boxIds[i])
                    .append("\",\"succeed\":true,\"resultCode\":\"OK\",\"resultMessage\":\"\"}");
        }
        return "{\"code\":200,\"data\":{\"responseList\":[" + list + "]}}";
    }
}
