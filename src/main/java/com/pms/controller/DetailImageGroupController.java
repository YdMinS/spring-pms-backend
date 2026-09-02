package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.DetailImageGroupRequest;
import com.pms.dto.response.DetailImageGroupResponse;
import com.pms.service.DetailImageGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Detail image group catalog CRUD (FEATURE_2609_03) — the tenant-wide list of detail-page image zones a
 * template's {@code imageZone} block may bind to.
 *
 * <p>ADMIN-only via the global {@code /api/admin/**} rules (SecurityConfig) — no per-method
 * {@code @PreAuthorize}. Reads/writes are tenant-scoped in the service.</p>
 */
@RestController
@RequestMapping("/api/admin/detail-image-groups")
@RequiredArgsConstructor
@Tag(name = "Detail Image Group", description = "Detail-page image zone catalog (ADMIN only)")
public class DetailImageGroupController {

    private final DetailImageGroupService detailImageGroupService;

    @GetMapping
    @Operation(summary = "List detail image groups with usage counts")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<DetailImageGroupResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(detailImageGroupService.list()));
    }

    @PostMapping
    @Operation(summary = "Create a detail image group (code is derived and immutable)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<DetailImageGroupResponse>> create(
            @Valid @RequestBody DetailImageGroupRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(detailImageGroupService.create(request)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a detail image group (display name only)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<DetailImageGroupResponse>> rename(
            @PathVariable Long id,
            @Valid @RequestBody DetailImageGroupRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(detailImageGroupService.rename(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a detail image group unused by any active template")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> delete(@PathVariable Long id) {
        detailImageGroupService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
