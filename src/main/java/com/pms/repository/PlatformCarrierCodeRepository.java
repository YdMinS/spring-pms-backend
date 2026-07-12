package com.pms.repository;

import com.pms.domain.PlatformCarrierCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformCarrierCodeRepository extends JpaRepository<PlatformCarrierCode, Long> {

    Optional<PlatformCarrierCode> findByCarrier_IdAndPlatform(Long carrierId, String platform);
}
