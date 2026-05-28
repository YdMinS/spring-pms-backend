package com.pms.repository;

import com.pms.domain.CommissionRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommissionRateRepository extends JpaRepository<CommissionRate, Long> {
    Optional<CommissionRate> findByPlatformAndCategoryId(String platform, Long categoryId);

    List<CommissionRate> findByPlatform(String platform);
}
