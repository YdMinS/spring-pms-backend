package com.pms.repository;

import com.pms.domain.CarrierRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CarrierRateRepository extends JpaRepository<CarrierRate, Long> {
    Optional<CarrierRate> findByIsDefaultTrue();
}
