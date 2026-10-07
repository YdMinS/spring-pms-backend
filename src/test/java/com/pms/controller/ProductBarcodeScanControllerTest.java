package com.pms.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/admin/products/barcode-scan}: real decode end to end (no mocks) — found / not found /
 * non-image 400 — plus authority (401 no token / 403 USER token).
 */
class ProductBarcodeScanControllerTest extends BaseIntegrationTest {

    private static final String URL = "/api/admin/products/barcode-scan";
    private static final String EAN13 = "8801234567893";

    @Test
    void scan_ean13Png_200WithBarcode() throws Exception {
        mockMvc.perform(multipart(URL).file(png("photo.png", ean13Png()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.barcode").value(EAN13))
                .andExpect(jsonPath("$.data.format").value("EAN_13"));
    }

    @Test
    void scan_blankPng_200WithNulls() throws Exception {
        mockMvc.perform(multipart(URL).file(png("blank.png", blankPng()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(content().string(containsString("\"barcode\":null")))
                .andExpect(content().string(containsString("\"format\":null")));
    }

    @Test
    void scan_nonImage_400() throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart(URL).file(text).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scan_noToken_401_userToken_403() throws Exception {
        mockMvc.perform(multipart(URL).file(png("photo.png", ean13Png())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart(URL).file(png("photo.png", ean13Png()))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    private MockMultipartFile png(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/png", bytes);
    }

    private byte[] ean13Png() throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(EAN13, BarcodeFormat.EAN_13, 400, 150);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }

    private byte[] blankPng() throws Exception {
        BufferedImage blank = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 400);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(blank, "PNG", out);
        return out.toByteArray();
    }
}
