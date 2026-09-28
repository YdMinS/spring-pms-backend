package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.OrderAcknowledgeRequest;
import com.pms.dto.request.ReservedInvoiceRequest;
import com.pms.dto.request.ReservedShipmentTimeRequest;
import com.pms.dto.request.ReservedStoredRequest;
import com.pms.dto.response.ReservationCreateResult;
import com.pms.dto.response.ReservedShipmentRowResponse;
import com.pms.dto.response.StoredInvoiceResponse;
import com.pms.service.ShipmentConfirmResult;
import com.pms.service.reservation.InternalStageResult;
import com.pms.service.reservation.ReservedShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 발송 (FEATURE_2609_75 / E5~E9 · E12~E16). ADMIN 전용 — 쿠팡에 되돌릴 수 없는 쓰기를 예약·실행한다.
 * 행 작업(E8·E9)의 경로 변수는 예약 결과 행 id(화면 단위는 주문, D30), 송장(E12)은 배송 묶음 id 다(D18).
 * ❌ 예약을 지금 실행하는 경로는 두지 않는다(D21 🔁 — dev 검증은 몇 분 뒤로 예약을 건다).
 */
@RestController
@RequestMapping("/api/admin/reserved-shipments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Reserved Shipment", description = "Reserved acknowledgement + invoice upload (ADMIN only)")
public class ReservedShipmentController {

    private final ReservedShipmentService reservedShipmentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Reserve shipment from carrier result file",
            description = "Stores order/box/carrier/invoice per INTERNAL_PREPARING box; the file itself is not stored")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Reserved/updated/excluded")
    @ApiResponse(responseCode = "400", description = "Past time, empty file, parse failure or no carrier code")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<ReservationCreateResult>> create(
            @RequestParam("file") MultipartFile file,
            @RequestParam("executeAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime executeAt) {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.create(file, executeAt)));
    }

    @GetMapping
    @Operation(summary = "List reserved shipment rows", description = "One row per order box: open rows + last 7 days (KST)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Rows")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<List<ReservedShipmentRowResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.list()));
    }

    @GetMapping("/orders/{externalOrderId}")
    @Operation(summary = "Reserved shipment history of one order", description = "All rows of the order, newest first")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Rows")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<List<ReservedShipmentRowResponse>>> listByOrder(
            @PathVariable String externalOrderId) {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.listByOrder(externalOrderId)));
    }

    @PostMapping("/items/cancel")
    @Operation(summary = "Cancel reservation of selected orders", description = "AWAITING_SHIPMENT → INTERNAL_PREPARING")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Changed/skipped")
    @ApiResponse(responseCode = "400", description = "Empty selection, no order line, or reservation running")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<InternalStageResult>> cancelItems(
            @Valid @RequestBody OrderAcknowledgeRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.cancelItems(request.orderItemIds())));
    }

    @PatchMapping("/items/{itemId}/execute-at")
    @Operation(summary = "Change execute time of one order", description = "Only before the first run; past time rejected")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Updated row")
    @ApiResponse(responseCode = "400", description = "Past time, running or already started")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    @ApiResponse(responseCode = "404", description = "Row not found")
    public ResponseEntity<ResponseDTO<ReservedShipmentRowResponse>> changeTime(
            @PathVariable Long itemId, @Valid @RequestBody ReservedShipmentTimeRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.changeTime(itemId, request.executeAt())));
    }

    @PostMapping("/items/{itemId}/retry")
    @Operation(summary = "Retry a stopped order now", description = "Resumes the box from its failed step")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Row after the run")
    @ApiResponse(responseCode = "400", description = "Not FAILED in a STOPPED reservation")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    @ApiResponse(responseCode = "404", description = "Row not found")
    public ResponseEntity<ResponseDTO<ReservedShipmentRowResponse>> retry(@PathVariable Long itemId) {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.retry(itemId)));
    }

    @GetMapping("/orders/{externalOrderId}/invoices")
    @Operation(summary = "Current invoices of one order", description = "One entry per internal-stage box (PAID); null when none")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Invoices")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<List<StoredInvoiceResponse>>> invoicesOfOrder(
            @PathVariable String externalOrderId) {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.invoicesOfOrder(externalOrderId)));
    }

    @PutMapping("/shipments/{orderShipmentId}/invoice")
    @Operation(summary = "Save carrier and invoice of one internal-stage box",
            description = "Any time while INTERNAL_PREPARING or AWAITING_SHIPMENT; rejected only while its reservation runs")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Saved invoice")
    @ApiResponse(responseCode = "400", description = "Unknown carrier code, blank invoice, not internal stage, or running")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<StoredInvoiceResponse>> changeInvoice(
            @PathVariable Long orderShipmentId, @Valid @RequestBody ReservedInvoiceRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.changeInvoice(
                orderShipmentId, request.deliveryCompanyCode(), request.invoiceNumber())));
    }

    @PostMapping("/stored")
    @Operation(summary = "Reserve with stored invoices", description = "No file; INTERNAL_PREPARING boxes with a stored invoice")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Reserved/excluded")
    @ApiResponse(responseCode = "400", description = "Past time, empty selection or no order line")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<ReservationCreateResult>> reserveStored(
            @Valid @RequestBody ReservedStoredRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                reservedShipmentService.reserveStored(request.orderItemIds(), request.executeAt())));
    }

    @PostMapping("/stored/ship-now")
    @Operation(summary = "Ship now with stored invoices", description = "Acknowledge then upload invoice, no file")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Same shape as the carrier-file upload result")
    @ApiResponse(responseCode = "400", description = "Empty selection or no order line")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<ShipmentConfirmResult>> shipStoredNow(
            @Valid @RequestBody OrderAcknowledgeRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(reservedShipmentService.shipStoredNow(request.orderItemIds())));
    }
}
