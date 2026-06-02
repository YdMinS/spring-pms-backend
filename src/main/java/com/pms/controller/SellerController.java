package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CreateSellerRequest;
import com.pms.dto.request.UpdateSellerRequest;
import com.pms.dto.response.SellerResponse;
import com.pms.service.SellerServiceImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/seller")
@RequiredArgsConstructor
public class SellerController {
    private final SellerServiceImpl sellerService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDTO<SellerResponse>> createSeller(@Valid @RequestBody CreateSellerRequest request) {
        SellerResponse response = sellerService.createSeller(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDTO<List<SellerResponse>>> getAllSellers() {
        List<SellerResponse> sellers = sellerService.getAllSellers();
        return ResponseEntity.ok(ResponseDTO.success(sellers));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDTO<SellerResponse>> getSellerById(@PathVariable Long id) {
        SellerResponse response = sellerService.getSellerById(id);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDTO<SellerResponse>> updateSeller(
        @PathVariable Long id,
        @Valid @RequestBody UpdateSellerRequest request
    ) {
        SellerResponse response = sellerService.updateSeller(id, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDTO<Void>> deleteSeller(@PathVariable Long id) {
        sellerService.deleteSeller(id);
        return ResponseEntity.ok(ResponseDTO.success((Void) null));
    }
}
