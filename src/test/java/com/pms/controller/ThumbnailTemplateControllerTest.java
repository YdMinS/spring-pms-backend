package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.FontAsset;
import com.pms.repository.FontAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Thumbnail template + preview + font endpoints. Covers authority (401/403/201), elements JSON
 * round-trip, and the preview JPEG contract. Font tenant-isolation is proven separately at the
 * repository level ({@code FontAssetTenantIsolationTest}) — the cleaner pattern for a non-@TenantId
 * entity, matching the existing tenant/*IsolationTest suite.
 */
public class ThumbnailTemplateControllerTest extends BaseIntegrationTest {

    @Autowired
    private FontAssetRepository fontAssetRepository;

    private Long systemFontId() {
        return fontAssetRepository.findByFamilyKeyAndTenantIdIsNull("SansSerif")
                .map(FontAsset::getId)
                .orElseThrow(() -> new IllegalStateException("system font not seeded"));
    }

    private Map<String, Object> textElement(Long fontId) {
        return Map.of(
                "type", "text",
                "bind", "productName",
                "region", Map.of("x", 0, "y", 0, "w", 300, "h", 100),
                "align", Map.of("h", "center", "v", "center"),
                "fontId", fontId,
                "color", "#000000",
                "maxFontSize", 40,
                "minFontSize", 10,
                "maxLines", 2);
    }

    private String templateJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", "쿠팡 기본",
                "canvasWidth", 300,
                "canvasHeight", 300,
                "elements", List.of(textElement(systemFontId()))));
    }

    // ---- Authority (MUST-KEEP): one representative endpoint, three ways ----

    @Test
    public void createTemplate_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/thumbnail-templates")
                        .contentType("application/json")
                        .content(templateJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void createTemplate_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/thumbnail-templates")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content(templateJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void createTemplate_adminToken_returns201() throws Exception {
        mockMvc.perform(post("/api/admin/thumbnail-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(templateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("쿠팡 기본"));
    }

    // ---- Happy path: elements JSON survives the create→get round-trip ----

    @Test
    public void createThenGet_preservesElements() throws Exception {
        String createResponse = mockMvc.perform(post("/api/admin/thumbnail-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(templateJson()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(createResponse).get("data").get("id").asLong();

        mockMvc.perform(get("/api/admin/thumbnail-templates/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.elements.length()").value(1))
                .andExpect(jsonPath("$.data.elements[0].bind").value("productName"))
                .andExpect(jsonPath("$.data.elements[0].region.w").value(300));
    }

    // ---- Preview: returns a JPEG body (non-persistent) ----

    @Test
    public void preview_adminInlineTemplate_returnsJpeg() throws Exception {
        String previewJson = objectMapper.writeValueAsString(Map.of(
                "template", Map.of(
                        "name", "preview",
                        "canvasWidth", 200,
                        "canvasHeight", 200,
                        "elements", List.of(textElement(systemFontId()))),
                "sampleBindings", Map.of("productName", "샘플 상품")));

        mockMvc.perform(post("/api/admin/thumbnail-templates/preview")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(previewJson))
                .andExpect(status().isOk())
                .andExpect(content().contentType(org.springframework.http.MediaType.IMAGE_JPEG))
                .andExpect(result -> {
                    byte[] body = result.getResponse().getContentAsByteArray();
                    if (body.length == 0) {
                        throw new AssertionError("preview JPEG body is empty");
                    }
                });
    }

    // ---- Font list: ADMIN sees the seeded system font ----

    @Test
    public void listFonts_adminSeesSystemFont() throws Exception {
        mockMvc.perform(get("/api/admin/fonts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.familyKey=='SansSerif')].system").value(org.hamcrest.Matchers.hasItem(true)));
    }
}
