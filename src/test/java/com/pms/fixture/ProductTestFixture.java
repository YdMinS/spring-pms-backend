package com.pms.fixture;

import com.pms.domain.Product;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
import com.pms.dto.response.ProductResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ProductTestFixture - Test data builders for Product-related tests
 * Provides 15+ helper methods for unit and integration testing
 */
public class ProductTestFixture {

    // ==================== Product Entity Builders ====================

    /**
     * Create a basic product with default values
     */
    public static Product createProduct() {
        return createProduct(1L);
    }

    /**
     * Create a product with specified ID
     */
    public static Product createProduct(Long id) {
        Product product = Product.builder()
                .id(id)
                .barcodeId(1234567890123L)
                .brand("Samsung")
                .price(new BigDecimal("999.99"))
                .productName("Galaxy S21")
                .store("Best Buy")
                .unit("KG")
                .volumeHeight("160mm")
                .volumeLong("75mm")
                .volumeShort("8.9mm")
                .weight("170g")
                .description("Flagship smartphone")
                .name("Samsung Galaxy S21")
                .active(true)
                .build();

        setTimestamps(product);
        return product;
    }

    /**
     * Create an inactive product
     */
    public static Product createInactiveProduct() {
        return createInactiveProduct(1L);
    }

    /**
     * Create an inactive product with specified ID
     */
    public static Product createInactiveProduct(Long id) {
        Product product = Product.builder()
                .id(id)
                .barcodeId(1234567890123L)
                .brand("Samsung")
                .price(new BigDecimal("999.99"))
                .productName("Galaxy S21")
                .store("Best Buy")
                .unit("KG")
                .volumeHeight("160mm")
                .volumeLong("75mm")
                .volumeShort("8.9mm")
                .weight("170g")
                .description("Flagship smartphone")
                .name("Samsung Galaxy S21")
                .active(false)
                .build();

        setTimestamps(product);
        return product;
    }

    /**
     * Helper method to set BaseEntity timestamps using Reflection
     */
    private static void setTimestamps(Product product) {
        LocalDateTime now = LocalDateTime.now();
        ReflectionTestUtils.setField(product, "createdAt", now);
        ReflectionTestUtils.setField(product, "updatedAt", now);
    }

    // ==================== CreateProductRequest Builders ====================

    /**
     * Create a basic valid product request
     */
    public static CreateProductRequest createProductRequest() {
        return createValidRequest();
    }

    /**
     * Create a valid product request (all required fields)
     */
    public static CreateProductRequest createValidRequest() {
        return CreateProductRequest.builder()
                .barcodeId(1234567890123L)
                .brand("Samsung")
                .price(new BigDecimal("999.99"))
                .productName("Galaxy S21")
                .store("Best Buy")
                .unit("KG")
                .volumeHeight("160mm")
                .volumeLong("75mm")
                .volumeShort("8.9mm")
                .weight("170g")
                .description("Flagship smartphone")
                .name("Samsung Galaxy S21")
                .build();
    }

    /**
     * Create a request with specified price
     */
    public static CreateProductRequest requestWithPrice(BigDecimal price) {
        return CreateProductRequest.builder()
                .barcodeId(1234567890123L)
                .brand("Samsung")
                .price(price)
                .productName("Galaxy S21")
                .store("Best Buy")
                .unit("KG")
                .volumeHeight("160mm")
                .volumeLong("75mm")
                .volumeShort("8.9mm")
                .weight("170g")
                .description("Flagship smartphone")
                .name("Samsung Galaxy S21")
                .build();
    }

    /**
     * Create a request with specified unit
     */
    public static CreateProductRequest requestWithUnit(String unit) {
        return CreateProductRequest.builder()
                .barcodeId(1234567890123L)
                .brand("Samsung")
                .price(new BigDecimal("999.99"))
                .productName("Galaxy S21")
                .store("Best Buy")
                .unit(unit)
                .volumeHeight("160mm")
                .volumeLong("75mm")
                .volumeShort("8.9mm")
                .weight("170g")
                .description("Flagship smartphone")
                .name("Samsung Galaxy S21")
                .build();
    }

    /**
     * Create a request with zero price (invalid)
     */
    public static CreateProductRequest requestWithZeroPrice() {
        return requestWithPrice(BigDecimal.ZERO);
    }

    /**
     * Create a request with negative price (invalid)
     */
    public static CreateProductRequest requestWithNegativePrice() {
        return requestWithPrice(new BigDecimal("-50.00"));
    }

    /**
     * Create a request with invalid unit
     */
    public static CreateProductRequest requestWithInvalidUnit() {
        return requestWithUnit("INVALID");
    }

    /**
     * Create a request with very low price
     */
    public static CreateProductRequest requestWithLowPrice() {
        return requestWithPrice(new BigDecimal("0.01"));
    }

    /**
     * Create a request with high price
     */
    public static CreateProductRequest requestWithHighPrice() {
        return requestWithPrice(new BigDecimal("99999.99"));
    }

    // ==================== UpdateProductRequest Builders ====================

    /**
     * Create an update request with all fields
     */
    public static UpdateProductRequest createUpdateRequest() {
        return UpdateProductRequest.builder()
                .brand("Apple")
                .price(new BigDecimal("1299.99"))
                .productName("iPhone 15")
                .store("Apple Store")
                .unit("G")
                .volumeHeight("147.8mm")
                .volumeLong("71.8mm")
                .volumeShort("7.80mm")
                .weight("171g")
                .description("Latest iPhone")
                .name("Apple iPhone 15")
                .active(true)
                .build();
    }

    /**
     * Create a partial update request
     */
    public static UpdateProductRequest createPartialUpdateRequest() {
        return UpdateProductRequest.builder()
                .brand("Apple")
                .price(new BigDecimal("1299.99"))
                .build();
    }

    // ==================== ProductResponse Builders ====================

    /**
     * Create a product response
     */
    public static ProductResponse createProductResponse() {
        return createProductResponse(1L);
    }

    /**
     * Create a product response with specified ID
     */
    public static ProductResponse createProductResponse(Long id) {
        return ProductResponse.builder()
                .id(id)
                .barcodeId(1234567890123L)
                .brand("Samsung")
                .price(new BigDecimal("999.99"))
                .productName("Galaxy S21")
                .store("Best Buy")
                .unit("KG")
                .volumeHeight("160mm")
                .volumeLong("75mm")
                .volumeShort("8.9mm")
                .weight("170g")
                .description("Flagship smartphone")
                .name("Samsung Galaxy S21")
                .active(true)
                .createdDate(LocalDateTime.now())
                .modifiedDate(LocalDateTime.now())
                .build();
    }

    // ==================== Product Category Builders ====================

    /**
     * Create a laptop product
     */
    public static Product createLaptopProduct() {
        return createLaptopProduct(1L);
    }

    /**
     * Create a laptop product with specified ID
     */
    public static Product createLaptopProduct(Long id) {
        Product product = Product.builder()
                .id(id)
                .barcodeId(9876543210123L)
                .brand("Dell")
                .price(new BigDecimal("1299.99"))
                .productName("XPS 15")
                .store("Best Buy")
                .unit("KG")
                .volumeHeight("359.5mm")
                .volumeLong("235mm")
                .volumeShort("18.9mm")
                .weight("1.94kg")
                .description("High-performance laptop")
                .name("Dell XPS 15")
                .active(true)
                .build();

        setTimestamps(product);
        return product;
    }

    /**
     * Create a smartphone product
     */
    public static Product createSmartphoneProduct() {
        return createSmartphoneProduct(2L);
    }

    /**
     * Create a smartphone product with specified ID
     */
    public static Product createSmartphoneProduct(Long id) {
        Product product = Product.builder()
                .id(id)
                .barcodeId(1111111111111L)
                .brand("Apple")
                .price(new BigDecimal("999.99"))
                .productName("iPhone 15")
                .store("Apple Store")
                .unit("G")
                .volumeHeight("147.8mm")
                .volumeLong("71.8mm")
                .volumeShort("7.80mm")
                .weight("171g")
                .description("Latest iPhone")
                .name("Apple iPhone 15")
                .active(true)
                .build();

        setTimestamps(product);
        return product;
    }

    /**
     * Create an electronics product request
     */
    public static CreateProductRequest createElectronicsProductRequest() {
        return CreateProductRequest.builder()
                .barcodeId(2222222222222L)
                .brand("Sony")
                .price(new BigDecimal("299.99"))
                .productName("WH-1000XM5")
                .store("Best Buy")
                .unit("G")
                .volumeHeight("195mm")
                .volumeLong("175mm")
                .volumeShort("80mm")
                .weight("250g")
                .description("Wireless headphones")
                .name("Sony WH-1000XM5")
                .build();
    }

    /**
     * Create an electronics product entity
     */
    public static Product createElectronicsProduct() {
        return createElectronicsProduct(3L);
    }

    /**
     * Create an electronics product entity with specified ID
     */
    public static Product createElectronicsProduct(Long id) {
        Product product = Product.builder()
                .id(id)
                .barcodeId(2222222222222L)
                .brand("Sony")
                .price(new BigDecimal("299.99"))
                .productName("WH-1000XM5")
                .store("Best Buy")
                .unit("G")
                .volumeHeight("195mm")
                .volumeLong("175mm")
                .volumeShort("80mm")
                .weight("250g")
                .description("Wireless headphones")
                .name("Sony WH-1000XM5")
                .active(true)
                .build();

        setTimestamps(product);
        return product;
    }

    // ==================== Unit Validation Helpers ====================

    /**
     * Get all valid units for testing
     */
    public static String[] getValidUnits() {
        return new String[]{"KG", "G", "L", "ML"};
    }

    /**
     * Get invalid units for testing
     */
    public static String[] getInvalidUnits() {
        return new String[]{"INVALID", "KGS", "GRAM", "LITER", "ML2", "", " "};
    }
}
