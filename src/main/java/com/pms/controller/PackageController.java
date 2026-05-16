package com.pms.controller;

import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import com.pms.service.PackageService;
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
    public ResponseEntity<PackageResponse> createPackage(@RequestBody PackageRequest request) {
        throw new UnsupportedOperationException();
    }

    @GetMapping
    public ResponseEntity<List<PackageResponse>> getPackages() {
        throw new UnsupportedOperationException();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PackageResponse> getPackage(@PathVariable Long id) {
        throw new UnsupportedOperationException();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PackageResponse> updatePackage(@PathVariable Long id, @RequestBody PackageRequest request) {
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePackage(@PathVariable Long id) {
        throw new UnsupportedOperationException();
    }
}
