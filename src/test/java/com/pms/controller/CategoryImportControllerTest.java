package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.category.ElevenstCategoryClient;
import com.pms.service.category.ElevenstFixtures;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/admin/category-import/coupang} authority (FEATURE_2608_06 / 53): ADMIN 200 (small fixture),
 * non-ADMIN 403, unauthenticated 401 — the ADMIN gate is the global {@code POST /api/admin/**} rule.
 * FEATURE_2610_10: the same three for {@code /elevenst} (the 11st download is mocked) and {@code /elevenst-fee}.
 */
class CategoryImportControllerTest extends BaseIntegrationTest {

    @MockBean private ElevenstCategoryClient elevenstCategoryClient;

    private MockMultipartFile fixtureFile() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("data");
            for (int i = 0; i < 3; i++) {
                sheet.createRow(i).createCell(0).setCellValue("헤더" + i);
            }
            sheet.createRow(3);
            Row row = sheet.createRow(4);
            row.createCell(0).setCellValue("[58646] 식품>가공/즉석식품>라면>봉지라면");
            row.createCell(1).setCellValue(10.6);
            wb.write(out);
            return new MockMultipartFile("file", "food.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    @Test
    void import_noToken_returns401() throws Exception {
        mockMvc.perform(multipart("/api/admin/category-import/coupang").file(fixtureFile()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void import_userToken_returns403() throws Exception {
        mockMvc.perform(multipart("/api/admin/category-import/coupang").file(fixtureFile())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void import_adminToken_returns200WithCounts() throws Exception {
        mockMvc.perform(multipart("/api/admin/category-import/coupang").file(fixtureFile())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leavesProcessed").value(1))
                .andExpect(jsonPath("$.data.mappingsCreated").value(1));
    }

    // ---- FEATURE_2610_10: 11st tree (D21 ①) and fee page (D22) ----

    private MockMultipartFile feeFile() {
        return new MockMultipartFile("file", "fee.html", "text/html", ElevenstFixtures.feeHtml());
    }

    @Test
    void importElevenst_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/category-import/elevenst"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void importElevenst_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/category-import/elevenst")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void importElevenst_adminToken_returns200WithCounts() throws Exception {
        given(elevenstCategoryClient.fetchCategoryXml()).willReturn(ElevenstFixtures.treeXml());

        mockMvc.perform(post("/api/admin/category-import/elevenst")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platformNodesCreated").value(24))
                .andExpect(jsonPath("$.data.nodesProcessed").value(24));
    }

    @Test
    void importElevenstFee_noToken_returns401() throws Exception {
        mockMvc.perform(multipart("/api/admin/category-import/elevenst-fee").file(feeFile()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void importElevenstFee_userToken_returns403() throws Exception {
        mockMvc.perform(multipart("/api/admin/category-import/elevenst-fee").file(feeFile())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void importElevenstFee_adminTokenAfterTree_returns200WithResult() throws Exception {
        given(elevenstCategoryClient.fetchCategoryXml()).willReturn(ElevenstFixtures.treeXml());
        mockMvc.perform(post("/api/admin/category-import/elevenst")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/admin/category-import/elevenst-fee").file(feeFile())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leavesUpdated").value(12))
                .andExpect(jsonPath("$.data.unmatchedCategories.length()").value(2))
                .andExpect(jsonPath("$.data.unmatchedExceptions.length()").value(2))
                .andExpect(jsonPath("$.data.borrowed[0].category").value("구강/면도"));
    }

    @Test
    void importElevenstFee_beforeTree_returns400() throws Exception {
        mockMvc.perform(multipart("/api/admin/category-import/elevenst-fee").file(feeFile())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("11번가 카테고리 목록을 먼저 들여와야 합니다."));
    }
}
