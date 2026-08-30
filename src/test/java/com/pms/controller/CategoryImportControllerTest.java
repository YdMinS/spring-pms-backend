package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/admin/category-import/coupang} authority (FEATURE_2608_06 / 53): ADMIN 200 (small fixture),
 * non-ADMIN 403, unauthenticated 401 — the ADMIN gate is the global {@code POST /api/admin/**} rule.
 */
class CategoryImportControllerTest extends BaseIntegrationTest {

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
}
