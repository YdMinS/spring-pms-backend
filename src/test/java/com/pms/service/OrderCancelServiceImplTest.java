package com.pms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.config.CoupangProperties;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderCancelAction;
import com.pms.domain.OrderCancelReason;
import com.pms.domain.OrderItem;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.repository.OrderCancelActionRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
 * OrderCancelServiceImpl 분류·그룹핑·전송 바디·응답 판정·write-back·이력 테스트 (FEATURE_2609_25).
 *
 * CoupangApiClient·OrderItemRepository·OrderCancelActionRepository·CoupangProperties 는 @Mock,
 * ObjectMapper 는 실제 인스턴스(요청 바디를 문자열로 캡처해 검증하기 위해).
 */
@ExtendWith(MockitoExtension.class)
class OrderCancelServiceImplTest {

    private static final String CANCEL_PATH =
            "/v2/providers/openapi/apis/api/v5/vendors/{vendorId}/orders/{orderId}/cancel";

    @Mock
    private CoupangApiClient coupangApiClient;
    @Mock
    private CoupangProperties coupangProperties;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderCancelActionRepository orderCancelActionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderCancelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderCancelServiceImpl(coupangApiClient, coupangProperties,
                orderItemRepository, orderCancelActionRepository, objectMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cancelSendsVendorItemIdsAndReceiptCountsPaired() throws Exception {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 3, 0, 0),
                line(12L, account, "700001", "300001", "5002", "ACCEPT", 1, 0, 0)));
        given(coupangProperties.getOrderCancelPath()).willReturn(CANCEL_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any()))
                .willReturn(success("CANCEL", "5001", "5002"));

        service.cancel(request(OrderCancelReason.OUT_OF_STOCK, line(11L, 2), line(12L, 1)));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient).post(anyString(), body.capture(), eq(account));
        JsonNode sent = objectMapper.readTree(body.getValue());
        assertThat(sent.get("orderId").asLong()).isEqualTo(300001L);
        // 두 배열의 길이·순서 일치가 API 계약이다.
        assertThat(sent.get("vendorItemIds")).hasSize(2);
        assertThat(sent.get("vendorItemIds").get(0).asLong()).isEqualTo(5001L);
        assertThat(sent.get("vendorItemIds").get(1).asLong()).isEqualTo(5002L);
        assertThat(sent.get("receiptCounts")).hasSize(2);
        assertThat(sent.get("receiptCounts").get(0).asInt()).isEqualTo(2);
        assertThat(sent.get("receiptCounts").get(1).asInt()).isEqualTo(1);
        assertThat(sent.get("bigCancelCode").asText()).isEqualTo("CANERR");
        assertThat(sent.get("middleCancelCode").asText()).isEqualTo("CCTTER");
        assertThat(sent.get("userId").asText()).isEqualTo("wing-user");
    }

    @Test
    void cancelSplitsRequestPerShipmentBox() throws Exception {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 1, 0, 0),
                line(12L, account, "700002", "300001", "5002", "ACCEPT", 1, 0, 0)));
        given(coupangProperties.getOrderCancelPath()).willReturn(CANCEL_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any()))
                .willReturn(success("CANCEL", "5001"), success("CANCEL", "5002"));

        service.cancel(request(OrderCancelReason.OUT_OF_STOCK, line(11L, 1), line(12L, 1)));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(coupangApiClient, times(2)).post(anyString(), body.capture(), any());
        JsonNode first = objectMapper.readTree(body.getAllValues().get(0)).get("vendorItemIds");
        JsonNode second = objectMapper.readTree(body.getAllValues().get(1)).get("vendorItemIds");
        assertThat(first).hasSize(1);
        assertThat(first.get(0).asLong()).isEqualTo(5001L);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).asLong()).isEqualTo(5002L);
    }

    @Test
    void cancelSkipsNonCancellableAndFullyCancelledLines() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "DEPARTURE", 1, 0, 0),
                line(12L, account, "700001", "300001", "5002", "ACCEPT", 2, 2, 0)));

        OrderCancelResult result = service.cancel(
                request(OrderCancelReason.OUT_OF_STOCK, line(11L, 1), line(12L, 1)));

        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
        assertThat(result.skipped()).hasSize(2);
        assertThat(result.skipped()).extracting(OrderCancelResult.SkippedLine::orderItemId)
                .containsExactly(11L, 12L);
        assertThat(result.skipped().get(0).reason()).isEqualTo("취소할 수 없는 상태입니다");
        assertThat(result.skipped().get(1).reason()).isEqualTo("이미 전량 취소된 주문입니다");
    }

    @Test
    void cancelMarksNonCoupangLineUnsupported() {
        MarketplaceAccount naver = account(2L, Platform.NAVER, "N001", "wing-user");
        MarketplaceAccount coupang = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, naver, "700001", "300001", "5001", "ACCEPT", 1, 0, 0),
                line(12L, coupang, null, "300002", "5002", "ACCEPT", 1, 0, 0)));

        OrderCancelResult result = service.cancel(
                request(OrderCancelReason.OUT_OF_STOCK, line(11L, 1), line(12L, 1)));

        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
        assertThat(result.unsupported()).extracting(OrderCancelResult.SkippedLine::orderItemId)
                .containsExactly(11L, 12L);
        assertThat(result.unsupported().get(0).reason())
                .isEqualTo("쿠팡 주문이 아니거나 배송번호가 없습니다");
    }

    @Test
    void cancelRejectsInvalidRequestBeforeSending() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 3, 1, 0)));

        // 취소 가능 수량(3 − 1 = 2) 초과 → 전체 요청을 400 으로 막는다(부분 전송 금지)
        assertThatThrownBy(() -> service.cancel(request(OrderCancelReason.OUT_OF_STOCK, line(11L, 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("취소 가능 수량");

        // 같은 라인 중복 → 조용히 합치지 않는다
        assertThatThrownBy(() -> service.cancel(
                request(OrderCancelReason.OUT_OF_STOCK, line(11L, 1), line(11L, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");

        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
    }

    @Test
    void cancelFailsLineWithoutWingId() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", null);
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 1, 0, 0)));

        OrderCancelResult result = service.cancel(request(OrderCancelReason.OUT_OF_STOCK, line(11L, 1)));

        verify(coupangApiClient, never()).post(anyString(), anyString(), any());
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).code()).isEqualTo("NO_WING_ID");
        assertThat(result.failed().get(0).orderItemId()).isEqualTo(11L);
    }

    @Test
    void cancelMarksFailedVendorItemIdsAsFailed() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 1, 0, 0),
                line(12L, account, "700001", "300001", "5002", "ACCEPT", 1, 0, 0)));
        given(coupangProperties.getOrderCancelPath()).willReturn(CANCEL_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(
                "{\"code\":200,\"message\":\"취소 가능한 개수보다 요청한 개수가 더 많습니다\",\"data\":{"
                        + "\"failedVendorItemIds\":[\"5001\"],"
                        + "\"receiptMap\":{\"90001\":{\"receiptId\":\"90001\",\"receiptType\":\"CANCEL\","
                        + "\"vendorItemIds\":[\"5002\"],\"totalCount\":1}}}}");

        OrderCancelResult result = service.cancel(
                request(OrderCancelReason.OUT_OF_STOCK, line(11L, 1), line(12L, 1)));

        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).orderItemId()).isEqualTo(11L);
        assertThat(result.failed().get(0).message())
                .isEqualTo("취소 가능한 개수보다 요청한 개수가 더 많습니다");
        assertThat(result.cancelled()).hasSize(1);
        assertThat(result.cancelled().get(0).orderItemId()).isEqualTo(12L);
        assertThat(result.succeededLines()).isEqualTo(1);
    }

    @Test
    void cancelFailsGroupWhenResponseHasNoData() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 1, 0, 0),
                line(12L, account, "700001", "300001", "5002", "ACCEPT", 1, 0, 0)));
        given(coupangProperties.getOrderCancelPath()).willReturn(CANCEL_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn("{\"code\":200}");

        OrderCancelResult result = service.cancel(
                request(OrderCancelReason.OUT_OF_STOCK, line(11L, 1), line(12L, 1)));

        // 스키마가 다르면 조용한 성공이 아니라 시끄러운 실패다.
        assertThat(result.cancelled()).isEmpty();
        assertThat(result.failed()).hasSize(2);
        assertThat(result.failed().get(0).code()).isEqualTo("ERROR");
        verify(orderItemRepository, never()).saveAll(anyList());
    }

    @Test
    void cancelWritesBackByReceiptType() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 2, 0, 0),
                line(12L, account, "700001", "300001", "5002", "INSTRUCT", 3, 0, 0)));
        given(coupangProperties.getOrderCancelPath()).willReturn(CANCEL_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(
                "{\"code\":200,\"data\":{\"failedVendorItemIds\":[],\"receiptMap\":{"
                        + "\"90001\":{\"receiptId\":\"90001\",\"receiptType\":\"CANCEL\","
                        + "\"vendorItemIds\":[\"5001\"],\"totalCount\":2},"
                        + "\"90002\":{\"receiptId\":\"90002\",\"receiptType\":\"STOP_SHIPMENT\","
                        + "\"vendorItemIds\":[\"5002\"],\"totalCount\":1}}}}");

        OrderCancelResult result = service.cancel(
                request(OrderCancelReason.OUT_OF_STOCK, line(11L, 2), line(12L, 1)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderItemRepository).saveAll(captor.capture());
        List<OrderItem> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        // CANCEL → cancelCount, STOP_SHIPMENT → holdCount, status 는 둘 다 불변
        assertThat(saved.get(0).getCancelCount()).isEqualTo(2);
        assertThat(saved.get(0).getHoldCount()).isZero();
        assertThat(saved.get(0).getStatus()).isEqualTo("ACCEPT");
        assertThat(saved.get(1).getCancelCount()).isZero();
        assertThat(saved.get(1).getHoldCount()).isEqualTo(1);
        assertThat(saved.get(1).getStatus()).isEqualTo("INSTRUCT");

        // 응답은 어느 컬럼이 늘었든 두 수량을 모두 담는다(D14)
        OrderCancelResult.CancelledLine cancelledFirst = result.cancelled().get(0);
        assertThat(cancelledFirst.resultCancelCount()).isEqualTo(2);
        assertThat(cancelledFirst.resultHoldCount()).isZero();
        assertThat(cancelledFirst.resultPurchasableQty()).isZero();
        assertThat(cancelledFirst.resultStatus()).isEqualTo("CANCELLED");
        assertThat(cancelledFirst.receiptType()).isEqualTo("CANCEL");

        OrderCancelResult.CancelledLine cancelledSecond = result.cancelled().get(1);
        assertThat(cancelledSecond.resultCancelCount()).isZero();
        assertThat(cancelledSecond.resultHoldCount()).isEqualTo(1);
        assertThat(cancelledSecond.resultPurchasableQty()).isEqualTo(2);
        assertThat(cancelledSecond.resultStatus()).isEqualTo("INSTRUCT");
        assertThat(result.succeededQty()).isEqualTo(3);
    }

    @Test
    void cancelKeepsSuccessWhenWriteBackFails() {
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 1, 0, 0)));
        given(coupangProperties.getOrderCancelPath()).willReturn(CANCEL_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any()))
                .willReturn(success("CANCEL", "5001"));
        willThrow(new RuntimeException("db down")).given(orderItemRepository).saveAll(anyList());

        OrderCancelResult result = service.cancel(request(OrderCancelReason.OUT_OF_STOCK, line(11L, 1)));

        assertThat(result.succeededLines()).isEqualTo(1);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void cancelRecordsHistoryForSuccessAndFailure() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@oclyx.com", "n/a", List.of()));
        MarketplaceAccount account = account(1L, Platform.COUPANG, "A001", "wing-user");
        given(orderItemRepository.findWithAccountByIdIn(any())).willReturn(List.of(
                line(11L, account, "700001", "300001", "5001", "ACCEPT", 1, 0, 0),
                line(12L, account, "700001", "300001", "5002", "ACCEPT", 1, 0, 0),
                line(13L, account, "700001", "300001", "5003", "DEPARTURE", 1, 0, 0)));
        given(coupangProperties.getOrderCancelPath()).willReturn(CANCEL_PATH);
        given(coupangApiClient.post(anyString(), anyString(), any())).willReturn(
                "{\"code\":200,\"message\":\"OK\",\"data\":{\"failedVendorItemIds\":[\"5002\"],"
                        + "\"receiptMap\":{\"90001\":{\"receiptId\":\"90001\",\"receiptType\":\"CANCEL\","
                        + "\"vendorItemIds\":[\"5001\"],\"totalCount\":1}}}}");

        service.cancel(request(OrderCancelReason.WRONG_PRICE,
                line(11L, 1), line(12L, 1), line(13L, 1)));

        ArgumentCaptor<OrderCancelAction> captor = ArgumentCaptor.forClass(OrderCancelAction.class);
        // 스킵된 라인(13)은 사용자가 고른 것이 아니라 서버가 걸러낸 건이라 이력에 남기지 않는다.
        verify(orderCancelActionRepository, times(2)).save(captor.capture());
        List<OrderCancelAction> rows = captor.getAllValues();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getReason()).isEqualTo(OrderCancelReason.WRONG_PRICE);
            assertThat(row.getPlatformReasonCode()).isEqualTo("CCPRER");
            assertThat(row.getCreatedBy()).isEqualTo("admin@oclyx.com");
            assertThat(row.getStatusAtSend()).isEqualTo("ACCEPT");
        });
        assertThat(rows.get(0).isSucceeded()).isTrue();
        assertThat(rows.get(0).getReceiptType()).isEqualTo("CANCEL");
        assertThat(rows.get(0).getReceiptId()).isEqualTo("90001");
        assertThat(rows.get(1).isSucceeded()).isFalse();
        assertThat(rows.get(1).getReceiptType()).isNull();
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private OrderCancelRequest request(OrderCancelReason reason, OrderCancelRequest.Line... lines) {
        return new OrderCancelRequest(List.of(lines), reason);
    }

    private OrderCancelRequest.Line line(Long orderItemId, int quantity) {
        return new OrderCancelRequest.Line(orderItemId, quantity);
    }

    private MarketplaceAccount account(Long id, Platform platform, String vendorId, String vendorUserId) {
        Seller seller = Seller.builder().id(id).sellerName("셀러" + id)
                .businessRegistration("123-45-6789" + id).build();
        return MarketplaceAccount.builder()
                .id(id).seller(seller).platform(platform).vendorId(vendorId).vendorUserId(vendorUserId)
                .accessKey("ak").secretKey("sk").isActive(true).build();
    }

    private OrderItem line(Long id, MarketplaceAccount account, String boxId, String orderId,
                           String itemId, String status, int orderCount, int cancelCount, int holdCount) {
        return OrderItem.builder()
                .id(id).marketplaceAccount(account).platform(account.getPlatform())
                .externalOrderId(orderId).externalBoxId(boxId).externalItemId(itemId)
                .orderCount(orderCount).cancelCount(cancelCount).holdCount(holdCount)
                .status(status).build();
    }

    /** 전량 성공 응답 — 하나의 접수에 vendorItemId 들이 묶인 모양. */
    private String success(String receiptType, String... vendorItemIds) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < vendorItemIds.length; i++) {
            if (i > 0) ids.append(",");
            ids.append("\"").append(vendorItemIds[i]).append("\"");
        }
        return "{\"code\":200,\"message\":\"OK\",\"data\":{\"failedVendorItemIds\":[],\"receiptMap\":{"
                + "\"90001\":{\"receiptId\":\"90001\",\"receiptType\":\"" + receiptType + "\","
                + "\"vendorItemIds\":[" + ids + "],\"totalCount\":" + vendorItemIds.length + "}}}}";
    }
}
