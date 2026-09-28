package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.PurchasePlaceRequest;
import com.pms.dto.response.PurchasePlaceResponse;
import com.pms.service.PurchasePlaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Purchase place list (FEATURE_2609_76 / D14).
 *
 * <p>🔴 Authority lives in {@code SecurityConfig}, not here: {@code GET /api/admin/purchase-places} is open to
 * every signed-in user (the product forms on web and mobile read it), while POST/PUT/DELETE fall under the
 * {@code /api/admin/**} ADMIN rules — the same split as {@code /api/admin/carriers}.</p>
 */
@RestController
@RequestMapping("/api/admin/purchase-places")
@RequiredArgsConstructor
@Tag(name = "Purchase Place", description = "Purchase place list (read: any user, write: ADMIN)")
public class PurchasePlaceController {

    private final PurchasePlaceService purchasePlaceService;

    @GetMapping
    @Operation(summary = "List purchase places with product counts",
            description = "Ordered by sortOrder. Seeds 이마트·코스트코·노브랜드 when the tenant's list is empty")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Purchase places retrieved successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<List<PurchasePlaceResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(purchasePlaceService.list()));
    }

    @PostMapping
    @Operation(summary = "Create a purchase place (ADMIN)", description = "Name must be unique within the tenant")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Purchase place created successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Duplicate name, or validation error (blank/too long)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<PurchasePlaceResponse>> create(
            @Valid @RequestBody PurchasePlaceRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchasePlaceService.create(request)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a purchase place (ADMIN)",
            description = "Every product using the place shows the new name — products hold the id")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Purchase place renamed successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Duplicate name, or validation error (blank/too long)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Purchase place not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<PurchasePlaceResponse>> rename(
            @PathVariable Long id,
            @Valid @RequestBody PurchasePlaceRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchasePlaceService.rename(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a purchase place no active product uses (ADMIN)",
            description = "400 with \"N개 물품이 사용 중입니다\" while an active product uses it")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Purchase place deleted successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "An active product still uses this place",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Purchase place not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Void>> delete(@PathVariable Long id) {
        purchasePlaceService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
