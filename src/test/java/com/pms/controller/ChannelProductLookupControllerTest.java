package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.listing.ChannelProductLookupService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마켓 상품 읽기 창구(2609_67 / 01)의 권한 — 검색·단건 모두 토큰 없으면 401, USER 토큰이면 403
 * ({@code /api/admin/**} 전역 규칙). {@link ChannelProductLookupService} 는 목이다.
 *
 * <p>🔴 실제 쿠팡을 치는 200 통합테스트는 만들지 않는다(계정·네트워크 의존) — 여기서는 인증만 본다.
 * 파싱·매핑은 {@code CoupangListingAdapterTest} 와 {@code ChannelProductLookupServiceTest} 가 본다.</p>
 */
class ChannelProductLookupControllerTest extends BaseIntegrationTest {

    @MockBean private ChannelProductLookupService channelProductLookupService;

    private static final String BASE = "/api/admin/channel-products";

    @Test
    void search_noToken_401_userToken_403() throws Exception {
        String url = BASE + "?sellerId=7&platform=COUPANG&name=생수";
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void detail_noToken_401_userToken_403() throws Exception {
        String url = BASE + "/222333444?sellerId=7&platform=COUPANG";
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
