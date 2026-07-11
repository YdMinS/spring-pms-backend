package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.ShippingLabelRow;
import com.pms.service.ShippingLabelService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ShippingLabelController 보안(401/403/200) + 다운로드 헤더 테스트.
 *
 * ShippingLabelService 를 @MockBean 처리해 컨트롤러 테스트가 쿠팡 API 를 타지 않게 한다.
 */
public class ShippingLabelControllerTest extends BaseIntegrationTest {

    @MockBean
    private ShippingLabelService shippingLabelService;

    @Test
    public void testDownloadWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/spreadsheet"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testDownloadWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/spreadsheet")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testDownloadWithAdminTokenReturnsXlsx() throws Exception {
        given(shippingLabelService.collectRows(any())).willReturn(List.<ShippingLabelRow>of());
        given(shippingLabelService.toXlsx(any())).willReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/admin/shipping-labels/spreadsheet")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        containsString("spreadsheetml.sheet")))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment")));
    }
}
