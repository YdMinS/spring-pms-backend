package com.pms.repository;

import com.pms.domain.ThumbnailPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ThumbnailPhoto} (FEATURE_2610_32 D5). Tenant-filtered by {@code @TenantId}.
 */
public interface ThumbnailPhotoRepository extends JpaRepository<ThumbnailPhoto, Long> {

    /** Every stage row of one master (master · sellers · listings). */
    List<ThumbnailPhoto> findByMasterProduct_Id(Long masterProductId);

    /** One stage row — {@code scopeKey} from {@code CollageScopes}. */
    Optional<ThumbnailPhoto> findByMasterProduct_IdAndScopeKey(Long masterProductId, String scopeKey);
}
