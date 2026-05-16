package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import com.pms.service.PackageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/package")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PackageController {

    private final PackageService packageService;

    @PostMapping
    public ResponseEntity<ResponseDTO<PackageResponse>> createPackage(@Valid @RequestBody PackageRequest request) {
        PackageResponse response = packageService.createPackage(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ResponseDTO.success(response));
    }

    @GetMapping
    public ResponseEntity<ResponseDTO<List<PackageResponse>>> getPackages() {
        List<PackageResponse> responses = packageService.getPackages();
        return ResponseEntity.ok(ResponseDTO.success(responses));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResponseDTO<PackageResponse>> getPackage(@PathVariable Long id) {
        PackageResponse response = packageService.getPackage(id);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ResponseDTO<PackageResponse>> updatePackage(@PathVariable Long id, @Valid @RequestBody PackageRequest request) {
        PackageResponse response = packageService.updatePackage(id, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDTO<Void>> deletePackage(@PathVariable Long id) {
        packageService.deletePackage(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
