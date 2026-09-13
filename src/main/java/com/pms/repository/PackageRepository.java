package com.pms.repository;

import com.pms.domain.BoxKind;
import com.pms.domain.Package;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PackageRepository extends JpaRepository<Package, Long> {

    Optional<Package> findByIsDefaultTrue();

    /**
     * Boxes of one kind (FEATURE_2609_40 / PLAN D21).
     *
     * <p>🔴 {@code BoxKind.PURCHASED} is what every SELLING-PRICE dropdown must ask for: a recycled box
     * costs 0, so one appearing there would eventually be picked as a default box and price goods off a
     * zero-cost box. The unfiltered {@code findAll} stays the default for the box-management and packing
     * screens, which must see recycled boxes.</p>
     */
    List<Package> findByBoxKind(BoxKind boxKind);
}
