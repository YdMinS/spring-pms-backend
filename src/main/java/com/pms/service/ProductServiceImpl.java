package com.pms.service;

import com.pms.domain.Product;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
import com.pms.dto.response.ProductResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final ImageStorageService imageStorageService;

    @Value("${upload.path}")
    private String uploadPath;

    @Override
    @Transactional
    public ProductResponse createProduct(CreateProductRequest request, MultipartFile image) {
        Product product = Product.builder()
                .barcodeId(request.getBarcodeId())
                .brand(request.getBrand())
                .productName(request.getProductName())
                .price(request.getPrice())
                .description(request.getDescription())
                .store(request.getStore())
                .unit(request.getUnit())
                .volumeHeight(request.getVolumeHeight())
                .volumeLong(request.getVolumeLong())
                .volumeShort(request.getVolumeShort())
                .weight(request.getWeight())
                .active(true)
                .imageUrl(null)
                .build();

        Product savedProduct = productRepository.save(product);

        if (image != null && !image.isEmpty()) {
            try {
                String imageUrl = imageStorageService.saveImage(image, savedProduct.getId());
                savedProduct = savedProduct.toBuilder()
                        .imageUrl(imageUrl)
                        .build();
                productRepository.save(savedProduct);
            } catch (IOException e) {
                log.error("Failed to save image for product: {}", savedProduct.getId(), e);
                throw new RuntimeException("Failed to upload image", e);
            }
        }

        return ProductResponse.of(savedProduct);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long id) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        return ProductResponse.of(product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        List<Product> products = productRepository.findAllByActiveTrue();
        return products.stream()
                .map(ProductResponse::of)
                .toList();
    }

    @Override
    @Transactional
    public ProductResponse updateProduct(Long id, UpdateProductRequest request) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        Product updatedProduct = product.toBuilder()
                .brand(request.getBrand() != null ? request.getBrand() : product.getBrand())
                .productName(request.getProductName() != null ? request.getProductName() : product.getProductName())
                .price(request.getPrice() != null ? request.getPrice() : product.getPrice())
                .description(request.getDescription() != null ? request.getDescription() : product.getDescription())
                .store(request.getStore() != null ? request.getStore() : product.getStore())
                .unit(request.getUnit() != null ? request.getUnit() : product.getUnit())
                .volumeHeight(request.getVolumeHeight() != null ? request.getVolumeHeight() : product.getVolumeHeight())
                .volumeLong(request.getVolumeLong() != null ? request.getVolumeLong() : product.getVolumeLong())
                .volumeShort(request.getVolumeShort() != null ? request.getVolumeShort() : product.getVolumeShort())
                .weight(request.getWeight() != null ? request.getWeight() : product.getWeight())
                .build();

        Product savedProduct = productRepository.save(updatedProduct);
        return ProductResponse.of(savedProduct);
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        if (product.getImageUrl() != null) {
            imageStorageService.deleteImage(product.getImageUrl());
        }

        Product deletedProduct = product.toBuilder()
                .active(false)
                .build();
        productRepository.save(deletedProduct);
    }

    @Override
    @Transactional
    public void deleteProductImage(Long id) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        if (product.getImageUrl() != null) {
            imageStorageService.deleteImage(product.getImageUrl());
            Product updatedProduct = product.toBuilder()
                    .imageUrl(null)
                    .build();
            productRepository.save(updatedProduct);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getProductImage(Long id) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        if (product.getImageUrl() == null) {
            throw new ResourceNotFoundException("Product image", id);
        }

        try {
            byte[] imageData = Files.readAllBytes(Paths.get(uploadPath + product.getImageUrl()));
            var mediaType = imageStorageService.detectMediaType(product.getImageUrl());
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(imageData);
        } catch (IOException e) {
            log.error("Failed to read image for product: {}", id, e);
            throw new ResourceNotFoundException("Product image", id);
        }
    }
}
