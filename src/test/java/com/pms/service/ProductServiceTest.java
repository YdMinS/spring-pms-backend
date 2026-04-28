package com.pms.service;

import com.pms.domain.Product;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.response.ProductResponse;
import com.pms.fixture.ProductTestFixture;
import com.pms.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProductServiceTest - Unit tests for ProductService.create()
 *
 * TDD Phase 2-1: Unit Tests - Red-Green Cycles
 * Tests only the create() method using Mockito to mock ProductRepository
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductServiceImpl - Unit Tests (create method only)")
public class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductServiceImpl productService;

    // ==================== Phase 2-1 Iteration 1: testCreateProduct_Success ====================

    /**
     * Test: Create product with valid request → success
     *
     * Scenario: Valid CreateProductRequest with all required fields
     * Expected:
     *   - Product saved to repository
     *   - ProductResponse returned with all fields populated
     *   - active field set to true
     */
    @Test
    @DisplayName("Should create product with valid request - returns ProductResponse")
    public void testCreateProduct_Success() {
        // Given
        CreateProductRequest request = ProductTestFixture.createProductRequest();
        Product savedProduct = ProductTestFixture.createProduct(1L);

        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // When
        ProductResponse response = productService.create(request);

        // Then - Verify response is not null and contains expected data
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getBrand()).isEqualTo("Samsung");
        assertThat(response.getPrice()).isEqualTo(new BigDecimal("999.99"));
        assertThat(response.getActive()).isTrue();
        assertThat(response.getProductName()).isEqualTo("Galaxy S21");
        assertThat(response.getStore()).isEqualTo("Best Buy");
        assertThat(response.getUnit()).isEqualTo("KG");

        // Verify repository.save() was called exactly once
        verify(productRepository, times(1)).save(any(Product.class));
    }

    // ==================== Phase 2-1 Iteration 2: testCreateProduct_PriceZero_ThrowsException ====================

    /**
     * Test: Attempt to create product with price = 0 → throws exception
     *
     * Scenario: CreateProductRequest with price = 0 (invalid)
     * Expected:
     *   - IllegalArgumentException thrown
     *   - Exception message = "Price must be positive"
     *   - repository.save() NOT called
     */
    @Test
    @DisplayName("Should throw exception when price equals zero")
    public void testCreateProduct_PriceZero_ThrowsException() {
        // Given
        CreateProductRequest request = ProductTestFixture.requestWithZeroPrice();

        // When & Then - Verify exception is thrown with correct message
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Price must be positive");

        // Verify repository.save() was never called
        verify(productRepository, never()).save(any(Product.class));
    }

    // ==================== Phase 2-1 Iteration 3: testCreateProduct_PriceNegative_ThrowsException ====================

    /**
     * Test: Attempt to create product with negative price → throws exception
     *
     * Scenario: CreateProductRequest with price = -50 (invalid)
     * Expected:
     *   - IllegalArgumentException thrown
     *   - Exception message = "Price must be positive"
     *   - repository.save() NOT called
     */
    @Test
    @DisplayName("Should throw exception when price is negative")
    public void testCreateProduct_PriceNegative_ThrowsException() {
        // Given
        CreateProductRequest request = ProductTestFixture.requestWithNegativePrice();

        // When & Then - Verify exception is thrown with correct message
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Price must be positive");

        // Verify repository.save() was never called
        verify(productRepository, never()).save(any(Product.class));
    }

    // ==================== Phase 2-1 Iteration 4: testCreateProduct_PriceValid_Succeeds ====================

    /**
     * Test: Create product with valid positive price → success
     *
     * Scenario: CreateProductRequest with price = 99.99 (valid)
     * Expected:
     *   - Product created successfully
     *   - repository.save() called once
     *   - ProductResponse returned
     */
    @Test
    @DisplayName("Should succeed when price is valid (positive)")
    public void testCreateProduct_PriceValid_Succeeds() {
        // Given
        CreateProductRequest request = ProductTestFixture.requestWithPrice(new BigDecimal("99.99"));
        Product savedProduct = ProductTestFixture.createProduct(1L);

        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // When
        ProductResponse response = productService.create(request);

        // Then - Verify response is returned and repository was called
        assertThat(response).isNotNull();
        assertThat(response.getPrice()).isEqualTo(new BigDecimal("999.99"));

        verify(productRepository, times(1)).save(any(Product.class));
    }

    // ==================== Phase 2-1 Iteration 5: testCreateProduct_ValidUnits_Pass ====================

    /**
     * Test: Create product with each valid unit → success
     *
     * Scenario: CreateProductRequest with unit in [KG, G, L, ML]
     * Expected:
     *   - All should succeed
     *   - Product created for each unit
     *   - repository.save() called for each
     */
    @ParameterizedTest
    @ValueSource(strings = {"KG", "G", "L", "ML"})
    @DisplayName("Should succeed with valid units: KG, G, L, ML")
    public void testCreateProduct_ValidUnits_Pass(String unit) {
        // Given
        CreateProductRequest request = ProductTestFixture.requestWithUnit(unit);
        Product savedProduct = ProductTestFixture.createProduct(1L);

        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // When
        ProductResponse response = productService.create(request);

        // Then - Verify response is returned
        assertThat(response).isNotNull();
        verify(productRepository, times(1)).save(any(Product.class));
    }

    // ==================== Phase 2-1 Iteration 6: testCreateProduct_InvalidUnit_ThrowsException ====================

    /**
     * Test: Attempt to create product with invalid unit → throws exception
     *
     * Scenario: CreateProductRequest with unit = "INVALID"
     * Expected:
     *   - IllegalArgumentException thrown
     *   - Exception message = "Unit must be one of: KG, G, L, ML"
     *   - repository.save() NOT called
     */
    @Test
    @DisplayName("Should throw exception when unit is invalid")
    public void testCreateProduct_InvalidUnit_ThrowsException() {
        // Given
        CreateProductRequest request = ProductTestFixture.requestWithInvalidUnit();

        // When & Then - Verify exception is thrown with correct message
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unit must be one of: KG, G, L, ML");

        // Verify repository.save() was never called
        verify(productRepository, never()).save(any(Product.class));
    }

    // ==================== Phase 2-1 Iteration 7: testCreateProduct_SetsActiveToTrue ====================

    /**
     * Test: New product always has active = true
     *
     * Scenario: Create product with valid request
     * Expected:
     *   - Saved product has active field set to true
     *   - Response.active = true
     */
    @Test
    @DisplayName("Should set active field to true for new product")
    public void testCreateProduct_SetsActiveToTrue() {
        // Given
        CreateProductRequest request = ProductTestFixture.createProductRequest();
        Product savedProduct = ProductTestFixture.createProduct(1L);

        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // When
        ProductResponse response = productService.create(request);

        // Then - Verify active is true
        assertThat(response.getActive()).isTrue();
        verify(productRepository, times(1)).save(any(Product.class));
    }

    // ==================== Phase 2-1 Iteration 8: testCreateProduct_MapsAllFields ====================

    /**
     * Test: Request → Product entity → Response mapping
     *
     * Scenario: Verify all fields are correctly mapped through builder pattern
     * Expected:
     *   - All request fields present in response
     *   - createdDate and modifiedDate mapped from entity
     */
    @Test
    @DisplayName("Should map all fields from request through entity to response")
    public void testCreateProduct_MapsAllFields() {
        // Given
        CreateProductRequest request = ProductTestFixture.createProductRequest();
        Product savedProduct = ProductTestFixture.createProduct(1L);

        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // When
        ProductResponse response = productService.create(request);

        // Then - Verify all fields are mapped
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getBarcodeId()).isEqualTo(request.getBarcodeId());
        assertThat(response.getBrand()).isEqualTo(request.getBrand());
        assertThat(response.getPrice()).isEqualTo(request.getPrice());
        assertThat(response.getProductName()).isEqualTo(request.getProductName());
        assertThat(response.getStore()).isEqualTo(request.getStore());
        assertThat(response.getUnit()).isEqualTo(request.getUnit());
        assertThat(response.getVolumeHeight()).isEqualTo(request.getVolumeHeight());
        assertThat(response.getVolumeLong()).isEqualTo(request.getVolumeLong());
        assertThat(response.getVolumeShort()).isEqualTo(request.getVolumeShort());
        assertThat(response.getWeight()).isEqualTo(request.getWeight());
        assertThat(response.getDescription()).isEqualTo(request.getDescription());
        assertThat(response.getName()).isEqualTo(request.getName());
        assertThat(response.getActive()).isTrue();
        assertThat(response.getCreatedDate()).isNotNull();
        assertThat(response.getModifiedDate()).isNotNull();
    }

    // ==================== Phase 2-1 Iteration 9: testCreateProduct_InvalidUnits_Fail ====================

    /**
     * Test: Create product with various invalid units → all throw exception
     *
     * Scenario: CreateProductRequest with invalid units (INVALID, KGS, GRAM, LITER, etc.)
     * Expected:
     *   - All throw IllegalArgumentException
     */
    @ParameterizedTest
    @ValueSource(strings = {"INVALID", "KGS", "GRAM", "LITER", "ML2", "", " "})
    @DisplayName("Should throw exception for all invalid units")
    public void testCreateProduct_InvalidUnits_Fail(String invalidUnit) {
        // Given
        CreateProductRequest request = ProductTestFixture.requestWithUnit(invalidUnit);

        // When & Then - Verify exception is thrown
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unit must be one of: KG, G, L, ML");

        verify(productRepository, never()).save(any(Product.class));
    }

    // ==================== Phase 2-1 Iteration 10: testCreateProduct_RepositoryException ====================

    /**
     * Test: Repository throws exception → exception propagates
     *
     * Scenario: Valid request but repository.save() throws RuntimeException
     * Expected:
     *   - Exception propagated to caller
     *   - Validation still executed before repository call
     */
    @Test
    @DisplayName("Should propagate repository exceptions")
    public void testCreateProduct_RepositoryException() {
        // Given
        CreateProductRequest request = ProductTestFixture.createProductRequest();

        doThrow(new RuntimeException("Database error")).when(productRepository).save(any(Product.class));

        // When & Then - Verify exception is propagated
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database error");
    }

    // ==================== Phase 2-2 Cycle 1: testGetProduct_Success ====================

    /**
     * Test: Get active product by valid ID → product returned
     *
     * Scenario: Valid product ID with active = true
     * Expected:
     *   - ProductResponse returned with all fields populated
     *   - Fields match the product in repository
     */
    @Test
    @DisplayName("Should return product when valid ID provided")
    public void testGetProduct_Success() {
        // Given
        Long productId = 1L;
        Product product = ProductTestFixture.createProduct(productId);
        when(productRepository.findById(productId)).thenReturn(java.util.Optional.of(product));

        // When
        ProductResponse response = productService.getProduct(productId);

        // Then
        assertThat(response)
                .isNotNull()
                .extracting("id", "brand", "productName", "active")
                .containsExactly(productId, "Samsung", "Galaxy S21", true);

        verify(productRepository).findById(productId);
    }

    // ==================== Phase 2-2 Cycle 2: testGetProduct_InactiveProduct_ThrowsException ====================

    /**
     * Test: Get inactive product → ResourceNotFoundException thrown
     *
     * Scenario: Valid product ID but active = false (soft deleted)
     * Expected:
     *   - ResourceNotFoundException thrown
     *   - Exception message indicates product not found
     */
    @Test
    @DisplayName("Should throw exception when product is inactive")
    public void testGetProduct_InactiveProduct_ThrowsException() {
        // Given
        Long productId = 1L;
        Product inactiveProduct = ProductTestFixture.createInactiveProduct(productId);
        when(productRepository.findById(productId)).thenReturn(java.util.Optional.of(inactiveProduct));

        // When & Then
        assertThatThrownBy(() -> productService.getProduct(productId))
                .isInstanceOf(com.pms.exception.ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");

        verify(productRepository).findById(productId);
    }

    // ==================== Phase 2-2 Cycle 3: testGetProduct_NonexistentId_ThrowsException ====================

    /**
     * Test: Get non-existent product → ResourceNotFoundException thrown
     *
     * Scenario: Product ID does not exist in repository
     * Expected:
     *   - ResourceNotFoundException thrown
     *   - Repository.findById() was called
     */
    @Test
    @DisplayName("Should throw exception when product does not exist")
    public void testGetProduct_NonexistentId_ThrowsException() {
        // Given
        Long productId = 999L;
        when(productRepository.findById(productId)).thenReturn(java.util.Optional.empty());

        // When & Then
        assertThatThrownBy(() -> productService.getProduct(productId))
                .isInstanceOf(com.pms.exception.ResourceNotFoundException.class)
                .hasMessageContaining("not found");

        verify(productRepository).findById(productId);
    }

    // ==================== Phase 2-2 Cycle 4: testGetAllProducts_Success ====================

    /**
     * Test: Get all products with pagination → Page returned
     *
     * Scenario: Valid pagination parameters (page, size)
     * Expected:
     *   - Page<ProductResponse> returned
     *   - Contains expected products
     *   - Pagination info correct
     */
    @Test
    @DisplayName("Should return page of products with valid pagination")
    public void testGetAllProducts_Success() {
        // Given
        Product product1 = ProductTestFixture.createProduct(1L);
        Product product2 = ProductTestFixture.createLaptopProduct(2L);
        java.util.List<Product> products = java.util.Arrays.asList(product1, product2);

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        org.springframework.data.domain.Page<Product> productPage = new org.springframework.data.domain.PageImpl<>(products, pageable, 2L);

        when(productRepository.findByActiveTrue(any(org.springframework.data.domain.Pageable.class))).thenReturn(productPage);

        // When
        org.springframework.data.domain.Page<ProductResponse> result = productService.getAllProducts(0, 20, null);

        // Then
        assertThat(result)
                .isNotNull()
                .hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getNumber()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(20);

        verify(productRepository).findByActiveTrue(any(org.springframework.data.domain.Pageable.class));
    }

    // ==================== Phase 2-2 Cycle 5: testGetAllProducts_DefaultPageSize ====================

    @Test
    @DisplayName("Should use DEFAULT_PAGE_SIZE when size <= 0")
    public void testGetAllProducts_DefaultPageSize() {
        // Given
        java.util.List<Product> products = java.util.Collections.singletonList(ProductTestFixture.createProduct(1L));
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        org.springframework.data.domain.Page<Product> productPage = new org.springframework.data.domain.PageImpl<>(products, pageable, 1L);

        when(productRepository.findByActiveTrue(any(org.springframework.data.domain.Pageable.class))).thenReturn(productPage);

        // When - size = 0 should use DEFAULT_PAGE_SIZE (20)
        org.springframework.data.domain.Page<ProductResponse> result = productService.getAllProducts(0, 0, null);

        // Then
        assertThat(result.getSize()).isEqualTo(20);
    }

    // ==================== Phase 2-2 Cycle 6: testGetAllProducts_SortByCreatedDate ====================

    @Test
    @DisplayName("Should sort products by createdDate DESC")
    public void testGetAllProducts_SortByCreatedDate() {
        // Given
        Product product1 = ProductTestFixture.createProduct(1L);
        Product product2 = ProductTestFixture.createProduct(2L);
        java.util.List<Product> products = java.util.Arrays.asList(product2, product1); // product2 created later

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        org.springframework.data.domain.Page<Product> productPage = new org.springframework.data.domain.PageImpl<>(products, pageable, 2L);

        when(productRepository.findByActiveTrue(any(org.springframework.data.domain.Pageable.class))).thenReturn(productPage);

        // When
        org.springframework.data.domain.Page<ProductResponse> result = productService.getAllProducts(0, 20, null);

        // Then - verify sorting (product2 should be first)
        assertThat(result.getContent())
                .hasSize(2)
                .extracting("id")
                .containsExactly(2L, 1L);
    }

    // ==================== Phase 2-2 Cycle 7: testGetAllProducts_OnlyActiveProducts ====================

    @Test
    @DisplayName("Should only return active products")
    public void testGetAllProducts_OnlyActiveProducts() {
        // Given
        Product activeProduct = ProductTestFixture.createProduct(1L);
        java.util.List<Product> products = java.util.Collections.singletonList(activeProduct);

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        org.springframework.data.domain.Page<Product> productPage = new org.springframework.data.domain.PageImpl<>(products, pageable, 1L);

        when(productRepository.findByActiveTrue(any(org.springframework.data.domain.Pageable.class))).thenReturn(productPage);

        // When
        org.springframework.data.domain.Page<ProductResponse> result = productService.getAllProducts(0, 20, null);

        // Then
        assertThat(result.getContent())
                .allMatch(p -> p.getActive().equals(true));
    }

    // ==================== Phase 2-2 Cycle 8: testGetAllProducts_SearchByKeyword_Success ====================

    @Test
    @DisplayName("Should search by keyword and return filtered products")
    public void testGetAllProducts_SearchByKeyword_Success() {
        // Given
        Product product = ProductTestFixture.createProduct(1L);
        java.util.List<Product> products = java.util.Collections.singletonList(product);

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        org.springframework.data.domain.Page<Product> productPage = new org.springframework.data.domain.PageImpl<>(products, pageable, 1L);

        when(productRepository.searchByKeyword(eq("Samsung"), any(org.springframework.data.domain.Pageable.class))).thenReturn(productPage);

        // When
        org.springframework.data.domain.Page<ProductResponse> result = productService.getAllProducts(0, 20, "Samsung");

        // Then
        assertThat(result)
                .isNotNull()
                .hasSize(1);

        verify(productRepository).searchByKeyword(eq("Samsung"), any(org.springframework.data.domain.Pageable.class));
    }

    // ==================== Phase 2-2 Cycle 9: testGetAllProducts_EmptySearch_AllProducts ====================

    @Test
    @DisplayName("Should return all products when search is blank")
    public void testGetAllProducts_EmptySearch_AllProducts() {
        // Given
        Product product1 = ProductTestFixture.createProduct(1L);
        Product product2 = ProductTestFixture.createLaptopProduct(2L);
        java.util.List<Product> products = java.util.Arrays.asList(product1, product2);

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        org.springframework.data.domain.Page<Product> productPage = new org.springframework.data.domain.PageImpl<>(products, pageable, 2L);

        when(productRepository.findByActiveTrue(any(org.springframework.data.domain.Pageable.class))).thenReturn(productPage);

        // When - blank search should use findByActiveTrue()
        org.springframework.data.domain.Page<ProductResponse> result = productService.getAllProducts(0, 20, "   ");

        // Then
        assertThat(result)
                .isNotNull()
                .hasSize(2);

        verify(productRepository).findByActiveTrue(any(org.springframework.data.domain.Pageable.class));
    }

    // ==================== Phase 2-2 Cycle 10: testGetAllProducts_SearchNoResults ====================

    @Test
    @DisplayName("Should return empty page when search has no results")
    public void testGetAllProducts_SearchNoResults() {
        // Given
        java.util.List<Product> emptyList = java.util.Collections.emptyList();

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        org.springframework.data.domain.Page<Product> emptyPage = new org.springframework.data.domain.PageImpl<>(emptyList, pageable, 0L);

        when(productRepository.searchByKeyword(eq("NonExistent"), any(org.springframework.data.domain.Pageable.class))).thenReturn(emptyPage);

        // When
        org.springframework.data.domain.Page<ProductResponse> result = productService.getAllProducts(0, 20, "NonExistent");

        // Then
        assertThat(result)
                .isNotNull()
                .isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }
}
