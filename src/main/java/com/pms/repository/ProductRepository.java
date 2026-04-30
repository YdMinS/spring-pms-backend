package com.pms.repository;

import com.pms.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * ProductRepository - JPA repository for Product entity
 *
 * Phase 2-1: Only uses save() (inherited from JpaRepository)
 * Phase 2-3: Adds findByActiveTrue() for getAllProducts
 * Phase 2-3: Adds searchByKeyword() for search functionality
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Find active products (for Phase 2-3 READ implementation)
     */
    Page<Product> findByActiveTrue(Pageable pageable);

    /**
     * Search products by keyword (for Phase 2-3 READ implementation)
     * Searches in productName, brand, description fields
     */
    @Query("SELECT p FROM Product p WHERE p.active = true " +
           "AND (LOWER(p.productName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(p.brand) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Product> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Check if a product with given barcode exists
     */
    boolean existsByBarcodeId(String barcodeId);
}
