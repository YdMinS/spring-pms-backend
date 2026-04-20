package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
import com.pms.dto.response.ProductResponse;
import com.pms.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseDTO<ProductResponse>> createProduct(
            @Valid @RequestPart("product") CreateProductRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        ProductResponse response = productService.createProduct(request, image);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDTO.success("Product created successfully", response));
    }

    @GetMapping
    public ResponseEntity<ResponseDTO<List<ProductResponse>>> getAllProducts() {
        List<ProductResponse> products = productService.getAllProducts();
        return ResponseEntity.ok(ResponseDTO.success("Products retrieved successfully", products));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResponseDTO<ProductResponse>> getProduct(@PathVariable Long id) {
        ProductResponse product = productService.getProduct(id);
        return ResponseEntity.ok(ResponseDTO.success("Product retrieved successfully", product));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ResponseDTO<ProductResponse>> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request) {
        ProductResponse product = productService.updateProduct(id, request);
        return ResponseEntity.ok(ResponseDTO.success("Product updated successfully", product));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseDTO<Void>> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(ResponseDTO.success("Product deleted successfully", null));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> getProductImage(@PathVariable Long id) {
        return productService.getProductImage(id);
    }

    @DeleteMapping("/{id}/image")
    public ResponseEntity<ResponseDTO<Void>> deleteProductImage(@PathVariable Long id) {
        productService.deleteProductImage(id);
        return ResponseEntity.ok(ResponseDTO.success("Product image deleted successfully", null));
    }
}
