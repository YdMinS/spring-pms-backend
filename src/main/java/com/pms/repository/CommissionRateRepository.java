package com.pms.repository;

import com.pms.domain.Category;
import com.pms.domain.CommissionRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommissionRateRepository extends JpaRepository<CommissionRate, Long> {
    Optional<CommissionRate> findByPlatformAndCategory(String platform, Category category);

    @Query("SELECT cr FROM CommissionRate cr WHERE cr.platform = :platform AND " +
           "(:categoryId IS NULL AND cr.category IS NULL OR cr.category.id = :categoryId)")
    Optional<CommissionRate> findByPlatformAndCategoryId(@Param("platform") String platform,
                                                         @Param("categoryId") Long categoryId);

    @Query("SELECT cr FROM CommissionRate cr LEFT JOIN FETCH cr.category")
    List<CommissionRate> findAll();

    List<CommissionRate> findByPlatform(String platform);
}
