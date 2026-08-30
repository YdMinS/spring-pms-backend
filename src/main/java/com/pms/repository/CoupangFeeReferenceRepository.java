package com.pms.repository;

import com.pms.domain.CoupangFeeReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Global reference data (no tenant scope). {@code findByDae} narrows the resolver's candidate set to a
 * major category; {@code findAll} (from {@link JpaRepository}) backs the seeder's idempotency count and
 * the leaf-name-only lookup path.
 */
public interface CoupangFeeReferenceRepository extends JpaRepository<CoupangFeeReference, Long> {

    List<CoupangFeeReference> findByDae(String dae);
}
