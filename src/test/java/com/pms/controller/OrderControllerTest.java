package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderItem;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.OrderItemRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.coupang.CoupangApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OrderController 통합 테스트 — 인증(401), 목록 조회(raw/secretKey 미노출), 동기화 트리거.
 * 외부 호출(CoupangApiClient)은 @MockBean 으로 빈 응답 처리(네트워크 차단).
 */
class OrderControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private MarketplaceAccountRepository marketplaceAccountRepository;
    @Autowired private OrderItemRepository orderItemRepository;

    @MockBean private CoupangApiClient coupangApiClient;   // 동기화 시 빈 데이터 반환

    @BeforeEach
    void seedOrder() {
        given(coupangApiClient.get(anyString(), anyString(), any()))
                .willReturn("{\"data\":[],\"nextToken\":\"\"}");

        Seller seller = sellerRepository.save(Seller.builder()
                .sellerName("테스트셀러").businessRegistration("123-45-67890").build());
        MarketplaceAccount account = marketplaceAccountRepository.save(MarketplaceAccount.builder()
                .seller(seller).platform("COUPANG").accountAlias("쿠팡본점")
                .vendorId("A00012345").accessKey("ak").secretKey("sk").isActive(true).build());
        orderItemRepository.save(OrderItem.builder()
                .marketplaceAccount(account).platform("COUPANG")
                .externalOrderId("O1").externalBoxId("B1").externalItemId("I1")
                .itemName("양말").orderCount(10).cancelCount(2).holdCount(1)
                .status("ACCEPT").raw("{\"secret\":\"should-not-leak\"}").build());
    }

    @Test
    void getOrders_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOrders_returnsList_withoutRawOrSecret() throws Exception {
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].externalItemId").value("I1"))
                .andExpect(jsonPath("$.data[0].purchasableQty").value(7))   // 10-(2+1)
                .andExpect(jsonPath("$.data[0].raw").doesNotExist())
                .andExpect(jsonPath("$.data[0].secretKey").doesNotExist());
    }

    @Test
    void postSync_returnsOrders() throws Exception {
        mockMvc.perform(post("/api/orders/sync").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncedAt").exists())
                .andExpect(jsonPath("$.data.orders[0].externalItemId").value("I1"))
                .andExpect(jsonPath("$.data.orders[0].raw").doesNotExist());
    }
}
