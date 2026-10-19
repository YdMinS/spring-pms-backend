package com.pms.repository;

import com.pms.domain.ThumbnailCollage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ThumbnailCollage} (FEATURE_2610_21 D38). Tenant-filtered by {@code @TenantId}.
 */
public interface ThumbnailCollageRepository extends JpaRepository<ThumbnailCollage, Long> {

    /** Every stage row of one master (master default · sellers · listings). */
    List<ThumbnailCollage> findByMasterProduct_Id(Long masterProductId);

    /** One stage row — {@code scopeKey} from {@code CollageScopes}. */
    Optional<ThumbnailCollage> findByMasterProduct_IdAndScopeKey(Long masterProductId, String scopeKey);
}
