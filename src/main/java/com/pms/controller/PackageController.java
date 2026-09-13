package com.pms.controller;

import com.pms.domain.BoxKind;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import com.pms.service.PackageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/package")
@RequiredArgsConstructor
@Tag(name = "Package", description = "Package management API")
public class PackageController {

    private final PackageService packageService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new package")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<PackageResponse>> createPackage(@Valid @RequestBody PackageRequest request) {
        PackageResponse response = packageService.createPackage(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ResponseDTO.success(response));
    }

    /**
     * Retrieve packages, optionally narrowed to one kind (FEATURE_2609_40 / PLAN D21).
     *
     * <p>🔴 Every SELLING-PRICE dropdown must pass {@code boxKind=PURCHASED}: a recycled box costs 0, and
     * one picked as a default box would price goods off a zero-cost box. No filter = every kind, because
     * the packing screen has to see recycled boxes.</p>
     */
    @GetMapping
    @Operation(summary = "Retrieve packages", description = "boxKind=PURCHASED|RECYCLED narrows the list; omit it for every kind")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<List<PackageResponse>>> getPackages(
        @Parameter(description = "Box kind filter (PURCHASED | RECYCLED). Omit for every kind")
        @RequestParam(name = "boxKind", required = false) BoxKind boxKind
    ) {
        List<PackageResponse> responses = packageService.getPackages(boxKind);
        return ResponseEntity.ok(ResponseDTO.success(responses));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Retrieve a specific package by ID")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Package not found", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<PackageResponse>> getPackage(
        @Parameter(description = "Package ID", required = true)
        @PathVariable Long id
    ) {
        PackageResponse response = packageService.getPackage(id);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an existing package")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Package not found", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<PackageResponse>> updatePackage(
        @Parameter(description = "Package ID", required = true)
        @PathVariable Long id,
        @Valid @RequestBody PackageRequest request
    ) {
        PackageResponse response = packageService.updatePackage(id, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Upload a photo for a box (ADMIN only, FEATURE_2609_40 / PLAN D26).
     * A box without a photo is drawn as a shape from its dimensions, so this is always optional.
     */
    @PostMapping("/{id}/image")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Upload a box image")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Invalid image file", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Package not found", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<PackageResponse>> uploadPackageImage(
        @Parameter(description = "Package ID", required = true)
        @PathVariable Long id,
        @RequestParam(name = "file") MultipartFile file
    ) {
        PackageResponse response = packageService.uploadImage(id, file);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a package")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Package not found", content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Void>> deletePackage(
        @Parameter(description = "Package ID", required = true)
        @PathVariable Long id
    ) {
        packageService.deletePackage(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
