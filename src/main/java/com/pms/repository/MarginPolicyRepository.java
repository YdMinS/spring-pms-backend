package com.pms.repository;

import com.pms.domain.MarginPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link MarginPolicy} (FEATURE_2608_06 / 3a).
 *
 * <p>All queries are tenant-filtered by {@code @TenantId} automatically — no manual tenant condition.
 * {@link #findBySellerIdAndPlatform} backs the (seller, platform) uniqueness guard.</p>
 */
public interface MarginPolicyRepository extends JpaRepository<MarginPolicy, Long> {

    /** For the (seller, platform) duplicate guard on create/update. Tenant-scoped by @TenantId. */
    Optional<MarginPolicy> findBySellerIdAndPlatform(Long sellerId, String platform);
}
