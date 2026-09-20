package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.BarcodeExtractionItem;
import com.pms.dto.response.BarcodeExtractionResult;
import com.pms.dto.response.BarcodeExtractionStatus;
import com.pms.service.barcode.BarcodeExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 바코드 추출 엔드포인트 (FEATURE_2609_65): 200 배선 + 권한(401 토큰 없음 / 403 USER 토큰) + 요청 크기 가드.
 * {@link BarcodeExtractionService} 는 mock — 실제 디코딩은 서비스·디코더 단위 테스트가 덮는다.
 */
class ProductBarcodeControllerTest extends BaseIntegrationTest {

    @MockBean private BarcodeExtractionService barcodeExtractionService;

    private static final String BASE = "/api/admin/products/barcode-extraction";

    @Test
    void extract_adminToken_200() throws Exception {
        given(barcodeExtractionService.extract(any())).willReturn(new BarcodeExtractionResult(1, 1,
                List.of(new BarcodeExtractionItem(1L, "물품", BarcodeExtractionStatus.EXTRACTED,
                        "8801234567893", "EAN_13", 7L))));

        mockMvc.perform(post(BASE).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.requested").value(1))
                .andExpect(jsonPath("$.data.extracted").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("EXTRACTED"))
                .andExpect(jsonPath("$.data.items[0].barcode").value("8801234567893"))
                .andExpect(jsonPath("$.data.items[0].productImageId").value(7));
    }

    @Test
    void extract_noToken_401_userToken_403() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[1]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[1]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void extract_51ids_400() throws Exception {
        String ids = IntStream.rangeClosed(1, 51).mapToObj(String::valueOf).collect(Collectors.joining(","));

        mockMvc.perform(post(BASE).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());
    }
}
