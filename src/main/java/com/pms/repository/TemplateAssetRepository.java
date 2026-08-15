package com.pms.repository;

import com.pms.domain.TemplateAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@link TemplateAsset} is {@code @TenantId}, so implicit query methods (findAll-family) are
 * auto-filtered to the current tenant — no manual tenant condition needed.
 *
 * <p>⚠️ PK {@code findById} is NOT tenant-filtered by Hibernate; cross-tenant guards on delete are the
 * service's responsibility (see {@code TemplateAssetServiceImpl.delete}).</p>
 */
public interface TemplateAssetRepository extends JpaRepository<TemplateAsset, Long> {

    /** Current tenant's assets, newest first (auto-scoped by {@code @TenantId}). */
    List<TemplateAsset> findAllByOrderByIdDesc();
}
