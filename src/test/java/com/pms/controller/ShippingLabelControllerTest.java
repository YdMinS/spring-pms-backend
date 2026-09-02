package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.ShippingLabelPreviewRow;
import com.pms.service.ShippingLabelService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ShippingLabelController 보안(401/403/200) + 다운로드 헤더 테스트.
 *
 * ShippingLabelService 를 @MockBean 처리해 컨트롤러 테스트가 쿠팡 API 를 타지 않게 한다.
 */
public class ShippingLabelControllerTest extends BaseIntegrationTest {

    @MockBean
    private ShippingLabelService shippingLabelService;

    // --- V2 preview ---

    @Test
    public void testPreviewReturnsRowsWithAdminToken() throws Exception {
        ShippingLabelPreviewRow row = new ShippingLabelPreviewRow(
                "302012345678:3823839899", "김철수", "01012345678", "06133", "주소",
                "양말 블랙 L", 2, 1, "3823839899",
                "4000019469460", "문앞", "302012345678", "셀러A", "COUPANG");
        given(shippingLabelService.previewRows(any())).willReturn(List.of(row));

        mockMvc.perform(get("/api/admin/shipping-labels/v2/preview")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rowKey").value(notNullValue()))
                .andExpect(jsonPath("$.data[0].parcelQuantity").value(1));
    }

    @Test
    public void testPreviewWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/v2/preview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testPreviewWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/v2/preview")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // --- V2 preview by order ---

    @Test
    public void testPreviewByOrderReturnsRowsWithAdminToken() throws Exception {
        ShippingLabelPreviewRow row = new ShippingLabelPreviewRow(
                "302012345678:3823839899", "김철수", "01012345678", "06133", "주소",
                "양말 블랙 L", 2, 1, "3823839899",
                "4000019469460", "문앞", "302012345678", "셀러A", "COUPANG");
        given(shippingLabelService.previewRowsByOrder(any())).willReturn(List.of(row));

        mockMvc.perform(get("/api/admin/shipping-labels/v2/preview/by-order")
                .param("orderItemId", "1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rowKey").value(notNullValue()))
                .andExpect(jsonPath("$.data[0].parcelQuantity").value(1));
    }

    @Test
    public void testPreviewByOrderRequiresOrderItemId() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/v2/preview/by-order")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testPreviewByOrderWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/v2/preview/by-order")
                .param("orderItemId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testPreviewByOrderWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/v2/preview/by-order")
                .param("orderItemId", "1")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // --- V2 export ---

    @Test
    public void testExportReturnsXlsxWithAdminToken() throws Exception {
        given(shippingLabelService.toXlsxFromExport(any())).willReturn(new byte[]{1, 2, 3});

        mockMvc.perform(post("/api/admin/shipping-labels/v2/spreadsheet")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody(2)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml.sheet")));
    }

    @Test
    public void testExportRejectsParcelQuantityZero() throws Exception {
        mockMvc.perform(post("/api/admin/shipping-labels/v2/spreadsheet")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody(0)))                              // @Min(1) 위반
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testExportWithoutToken() throws Exception {
        mockMvc.perform(post("/api/admin/shipping-labels/v2/spreadsheet")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody(1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testExportWithUserToken() throws Exception {
        mockMvc.perform(post("/api/admin/shipping-labels/v2/spreadsheet")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(exportBody(1)))
                .andExpect(status().isForbidden());
    }

    /** 편집된 export 요청 바디 1행 (parcelQuantity 는 파라미터). */
    private String exportBody(int parcelQuantity) {
        return """
            {"rows":[{"receiverName":"김철수","receiverPhone":"01012345678","postCode":"06133",
                      "address":"주소","productName":"양말 블랙 L","quantity":2,"parcelQuantity":%d,
                      "orderId":"4000019469460","deliveryMessage":"문앞","shipmentBoxId":"302012345678",
                      "sellerName":"셀러A","platform":"COUPANG"}]}
            """.formatted(parcelQuantity);
    }
}
