package com.pms.service;

import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
import com.pms.dto.response.ProductResponse;
import org.springframework.data.domain.Page;

/**
 * ProductService - Service interface for Product management
 */
public interface ProductService {
    ProductResponse create(CreateProductRequest request);
    ProductResponse getProduct(Long id);
    Page<ProductResponse> getAllProducts(int page, int size, String search);
    ProductResponse updateProduct(Long id, UpdateProductRequest request);
}
