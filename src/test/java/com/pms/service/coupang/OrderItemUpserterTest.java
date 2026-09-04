package com.pms.service.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.repository.OrderItemRepository;
import com.pms.service.coupang.OrderItemUpserter.UpsertCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * OrderItemUpserter 적재 규칙 테스트 (3개 경로 공통 진입점).
 *
 * ObjectMapper 는 실제 인스턴스 — 쿠팡 응답 JSON 문자열을 그대로 먹여야 매핑을 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class OrderItemUpserterTest {

    private static final String BOX_ID = "700000012345";
    private static final String ORDER_ID = "300000012345";
    private static final String ITEM_ID = "800000012345";

    @Mock
    private OrderItemRepository orderItemRepository;

    @InjectMocks
    private OrderItemUpserter upserter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        account = MarketplaceAccount.builder()
                .id(1L)
                .platform("COUPANG")
                .vendorId("A00012345")
                .accessKey("ak").secretKey("sk")
                .isActive(true)
                .build();
    }

    @Test
    void upsertBox신규라인은insert() {
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                eq(1L), eq(BOX_ID), eq(ORDER_ID), eq(ITEM_ID)))
                .willReturn(Optional.empty());

        UpsertCount result = upserter.upsertBox(account, box(oneLineBox()));

        ArgumentCaptor<OrderItem> saved = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository, times(1)).save(saved.capture());
        OrderItem line = saved.getValue();
        assertThat(line.getId()).isNull();                                  // 신규
        assertThat(line.getStatus()).isEqualTo("INSTRUCT");
        assertThat(line.getPlatform()).isEqualTo("COUPANG");
        assertThat(line.getExternalOrderId()).isEqualTo(ORDER_ID);
        assertThat(line.getExternalBoxId()).isEqualTo(BOX_ID);
        assertThat(line.getExternalItemId()).isEqualTo(ITEM_ID);
        assertThat(line.getOrderCount()).isEqualTo(3);
        assertThat(line.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 9, 4, 1, 0, 0));
        assertThat(result).isEqualTo(new UpsertCount(1, 0));
    }

    @Test
    void upsertBox기존라인은가변필드만갱신() {
        OrderItem existing = OrderItem.builder()
                .id(99L)
                .marketplaceAccount(account)
                .platform("COUPANG")
                .externalOrderId(ORDER_ID).externalBoxId(BOX_ID).externalItemId(ITEM_ID)
                .orderCount(1).cancelCount(0).holdCount(0)
                .status("ACCEPT")
                .build();
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                any(), any(), any(), any()))
                .willReturn(Optional.of(existing));

        UpsertCount result = upserter.upsertBox(account, box(oneLineBox()));

        ArgumentCaptor<OrderItem> saved = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository).save(saved.capture());
        OrderItem line = saved.getValue();
        assertThat(line.getId()).isEqualTo(99L);                            // 새 행이 아니다
        assertThat(line.getStatus()).isEqualTo("INSTRUCT");                 // 갱신됨
        assertThat(line.getOrderCount()).isEqualTo(3);
        assertThat(line.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 9, 4, 1, 0, 0));
        assertThat(line.getRaw()).contains(ITEM_ID);
        assertThat(result).isEqualTo(new UpsertCount(0, 1));
    }

    @Test
    void 박스에라인이여러개면각각upsert() {
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                any(), any(), any(), any()))
                .willReturn(Optional.empty());

        UpsertCount result = upserter.upsertBox(account, box(twoLineBox()));

        verify(orderItemRepository, times(2)).save(any(OrderItem.class));
        assertThat(result).isEqualTo(new UpsertCount(2, 0));
    }

    @Test
    void 주문자명이100자를넘으면잘라서저장() {
        String longName = "가".repeat(120);
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                any(), any(), any(), any()))
                .willReturn(Optional.empty());

        upserter.upsertBox(account, box(boxWithOrdererName(longName)));

        ArgumentCaptor<OrderItem> saved = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository).save(saved.capture());
        assertThat(saved.getValue().getOrdererName()).hasSize(100);
        // vendorItemName 은 trim 대상이 아니다(item_name = length 500, 무가공 저장)
        assertThat(saved.getValue().getItemName()).isEqualTo("양말A");
    }

    @Test
    void paidAt오프셋파싱실패시null() {
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                any(), any(), any(), any()))
                .willReturn(Optional.empty());

        UpsertCount result = upserter.upsertBox(account, box(boxWithPaidAt("not-a-date")));

        ArgumentCaptor<OrderItem> saved = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository).save(saved.capture());
        assertThat(saved.getValue().getPaidAt()).isNull();
        assertThat(result).isEqualTo(new UpsertCount(1, 0));                // 저장 자체는 성공
    }

    @Test
    void upsertBoxes는페이지전체를누적() {
        given(orderItemRepository.findByMarketplaceAccount_IdAndExternalBoxIdAndExternalOrderIdAndExternalItemId(
                any(), any(), any(), any()))
                .willReturn(Optional.empty());

        UpsertCount result = upserter.upsertBoxes(
                account, List.of(box(oneLineBox()), box(twoLineBox())));

        verify(orderItemRepository, times(3)).save(any(OrderItem.class));
        assertThat(result).isEqualTo(new UpsertCount(3, 0));
    }

    // --- helpers ---

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
             "orderItems":[{"vendorItemId":"%s","vendorItemName":"양말A","shippingCount":3}]}
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
