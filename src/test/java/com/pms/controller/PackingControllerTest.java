package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.ParcelStatus;
import com.pms.dto.response.OrderDetailResponse;
import com.pms.dto.response.PackingHistoryView;
import com.pms.dto.response.PackingScanResponse;
import com.pms.dto.response.ParcelCloseResponse;
import com.pms.dto.response.ParcelCompleteResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.service.order.OrderDetailService;
import com.pms.service.packing.PackingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final String RESTORE_PATH = "/api/admin/packing/parcels/1/restore";
    private static final String HISTORY_PATH = "/api/admin/packing/history";
    private static final String BOX_PATH = "/api/admin/packing/parcels/1/box";
    private static final String BOX_BODY = "{\"boxPackageId\":8}";
    private static final String ORDER_PATH = "/api/admin/packing/parcels/1/order";
    private static final String COMPLETE_BODY =
            "{\"boxPackageId\":7,\"items\":[{\"orderLineId\":11,\"productId\":33,\"quantity\":2}]}";
    private static final String CANDIDATES_BODY = "{\"items\":[{\"productId\":33,\"quantity\":2}]}";

    @MockBean
    private PackingService packingService;

    @MockBean
    private OrderDetailService orderDetailService;

    @Test
    public void testScanWithAdminTokenReturnsParcel() throws Exception {
        given(packingService.scan(any())).willReturn(new PackingScanResponse(
                new PackingScanResponse.ParcelView(1L, "123456789012", "롯데택배", 1, 1,
                        ParcelStatus.PENDING),
                new PackingScanResponse.OrderView("ORD-1", "셀러A", "김주문", "박수취", "COUPANG", "본계정"),
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

    // ── FEATURE_2611_03: restore + history (PLAN D9 · D13 · D14 · D32 · D34) ──────────────────────

    @Test
    public void testRestoreWithAdminTokenReturnsPending() throws Exception {
        given(packingService.restore(1L)).willReturn(new ParcelCloseResponse(1L, ParcelStatus.PENDING));

        mockMvc.perform(post(RESTORE_PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parcelId").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    public void testRestoreRequiresAuth() throws Exception {
        mockMvc.perform(post(RESTORE_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRestoreForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post(RESTORE_PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testHistoryWithAdminTokenPassesPeriodAndKeyword() throws Exception {
        given(packingService.history(eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 2)), eq("2558-2825")))
                .willReturn(List.of(new PackingHistoryView(5L, LocalDateTime.of(2026, 11, 2, 9, 30), 1L,
                        "255828253026", "롯데택배", "ORD-1", "셀러A", ParcelStatus.PENDING, ParcelStatus.UNUSED,
                        "packer@oclyx.com", "3호 상자", 7L)));

        mockMvc.perform(get(HISTORY_PATH).param("from", "2026-11-01").param("to", "2026-11-02")
                        .param("keyword", "2558-2825")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invoiceNumber").value("255828253026"))
                .andExpect(jsonPath("$.data[0].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$.data[0].toStatus").value("UNUSED"))
                .andExpect(jsonPath("$.data[0].changedBy").value("packer@oclyx.com"))
                .andExpect(jsonPath("$.data[0].boxName").value("3호 상자"))
                .andExpect(jsonPath("$.data[0].boxPackageId").value(7));
    }

    @Test
    public void testHistoryRequiresAuth() throws Exception {
        mockMvc.perform(get(HISTORY_PATH).param("from", "2026-11-01").param("to", "2026-11-02"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testHistoryForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get(HISTORY_PATH).param("from", "2026-11-01").param("to", "2026-11-02")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ── FEATURE_2611_06: box change of a packed parcel (PLAN D8 · D12) ──────────────────────

    @Test
    public void testChangeBoxWithAdminTokenReturnsCostsAfterChange() throws Exception {
        given(packingService.changeBox(1L, 8L)).willReturn(new ParcelCompleteResponse(1L, ParcelStatus.PACKED, 3,
                List.of(), new BigDecimal("500.00"), new BigDecimal("450"), new BigDecimal("3000.00")));

        mockMvc.perform(post(BOX_PATH).contentType(MediaType.APPLICATION_JSON).content(BOX_BODY)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parcelId").value(1))
                .andExpect(jsonPath("$.data.status").value("PACKED"))
                .andExpect(jsonPath("$.data.packedQty").value(3))
                .andExpect(jsonPath("$.data.actualBoxCost").value(450));
    }

    @Test
    public void testChangeBoxRequiresAuth() throws Exception {
        mockMvc.perform(post(BOX_PATH).contentType(MediaType.APPLICATION_JSON).content(BOX_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testChangeBoxForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post(BOX_PATH).contentType(MediaType.APPLICATION_JSON).content(BOX_BODY)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ── FEATURE_2611_07: shared order detail from a 「오늘 완료」 row (PLAN D22 · D30 · D31) ──────────────

    @Test
    public void testParcelOrderWithAdminTokenReturnsOrderDetail() throws Exception {
        given(orderDetailService.byParcel(1L)).willReturn(new OrderDetailResponse("ORD-1", "셀러A", "COUPANG", "본계정",
                "김주문", "박수취", LocalDateTime.of(2026, 11, 3, 9, 15), List.of(),
                List.of(new OrderDetailResponse.ParcelView(1L, "123456789012", "롯데택배", 1, ParcelStatus.PACKED,
                        LocalDateTime.of(2026, 11, 3, 10, 30), "3호 상자", 7L,
                        List.of(new OrderDetailResponse.ParcelItemView(33L, "양말A", "products/33.png", 3))))));

        mockMvc.perform(get(ORDER_PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.externalOrderId").value("ORD-1"))
                .andExpect(jsonPath("$.data.sellerName").value("셀러A"))
                .andExpect(jsonPath("$.data.accountAlias").value("본계정"))
                .andExpect(jsonPath("$.data.parcels[0].status").value("PACKED"))
                .andExpect(jsonPath("$.data.parcels[0].boxPackageId").value(7))
                .andExpect(jsonPath("$.data.parcels[0].items[0].quantity").value(3));
    }

    @Test
    public void testParcelOrderRequiresAuth() throws Exception {
        mockMvc.perform(get(ORDER_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testParcelOrderForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get(ORDER_PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testParcelOrderUnknownParcelReturnsNotFound() throws Exception {
        given(orderDetailService.byParcel(1L)).willThrow(new ResourceNotFoundException("ShipmentParcel", 1L));

        mockMvc.perform(get(ORDER_PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
