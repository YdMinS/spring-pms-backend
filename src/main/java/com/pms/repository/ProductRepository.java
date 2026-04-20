package com.pms.repository;

import com.pms.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findAllByActiveTrue();
    Optional<Product> findByIdAndActiveTrue(Long id);
    Optional<Product> findByBarcodeId(Long barcodeId);
}
