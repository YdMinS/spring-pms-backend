package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ParcelStatus;
import com.pms.dto.response.PackingScanResponse;
import com.pms.service.packing.PackingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PackingController 보안(401/403/200) 테스트 (FEATURE_2609_40 / 03).
 *
 * <p>{@link PackingService} 를 {@code @MockBean} 으로 갈아 끼워 컨트롤러 테스트가 재고 원장을 건드리지
 * 않게 한다 — 완료는 되돌릴 수 없는 출고를 남기는 경로다.
 */
public class PackingControllerTest extends BaseIntegrationTest {

    private static final String SCAN_PATH = "/api/admin/packing/scan";
    private static final String COMPLETE_PATH = "/api/admin/packing/parcels/1/complete";
    private static final String UNUSED_PATH = "/api/admin/packing/parcels/1/unused";
    private static final String CANDIDATES_PATH = "/api/admin/packing/box-candidates";
    private static final String COMPLETE_BODY =
            "{\"boxPackageId\":7,\"items\":[{\"orderLineId\":11,\"productId\":33,\"quantity\":2}]}";
    private static final String CANDIDATES_BODY = "{\"items\":[{\"productId\":33,\"quantity\":2}]}";

    @MockBean
    private PackingService packingService;

    @Test
    public void testScanWithAdminTokenReturnsParcel() throws Exception {
        given(packingService.scan(any())).willReturn(new PackingScanResponse(
                new PackingScanResponse.ParcelView(1L, "123456789012", "롯데택배", 1, 1,
                        ParcelStatus.PENDING),
                new PackingScanResponse.OrderView("ORD-1", "셀러A"),
                List.of(), List.of(), List.of(), true));

        mockMvc.perform(get(SCAN_PATH).param("invoiceNumber", "123456789012")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.parcel.invoiceNumber").value("123456789012"))
                .andExpect(jsonPath("$.data.isLastParcel").value(true));
    }

    @Test
    public void testScanRequiresAuth() throws Exception {
        mockMvc.perform(get(SCAN_PATH).param("invoiceNumber", "123456789012"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testCompleteForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post(COMPLETE_PATH).contentType(MediaType.APPLICATION_JSON).content(COMPLETE_BODY)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testUnusedForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post(UNUSED_PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testBoxCandidatesForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post(CANDIDATES_PATH).contentType(MediaType.APPLICATION_JSON).content(CANDIDATES_BODY)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testCompleteWithEmptyItemsReturnsBadRequest() throws Exception {
        mockMvc.perform(post(COMPLETE_PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"boxPackageId\":7,\"items\":[]}")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }
}
