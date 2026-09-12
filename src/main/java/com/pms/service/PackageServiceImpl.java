package com.pms.service;

import com.pms.domain.Package;
import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.PackageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service implementation for Package management.
 *
 * Handles all business logic including:
 * - isDefault uniqueness maintenance
 * - Data persistence via PackageRepository
 * - Transactional boundaries
 *
 * @see PackageService for interface contract
 * @see PackageRepository for data access
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PackageServiceImpl implements PackageService {

    private final PackageRepository packageRepository;

    @Override
    @Transactional
    public PackageResponse createPackage(PackageRequest request) {
        if (request.getIsDefault()) {
            packageRepository.findByIsDefaultTrue().ifPresent(existing -> {
                // toBuilder(), never a hand-copied Package.builder() (PLAN 2609_38 D7): a hand copy
                // silently drops every column it forgets — the box dimensions were its first victim.
                packageRepository.save(existing.toBuilder().isDefault(false).build());
            });
        }

        Package pkg = Package.builder()
            .type(request.getType())
            .cost(request.getCost())
            .effectiveDate(request.getEffectiveDate())
            .isDefault(request.getIsDefault())
            .widthCm(request.getWidthCm())
            .lengthCm(request.getLengthCm())
            .heightCm(request.getHeightCm())
            .build();

        Package saved = packageRepository.save(pkg);
        return toResponse(saved);
    }

    @Override
    public PackageResponse getPackage(Long id) {
        Package pkg = packageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Package", id));
        return toResponse(pkg);
    }

    @Override
    public List<PackageResponse> getPackages() {
        return packageRepository.findAll()
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public PackageResponse updatePackage(Long id, PackageRequest request) {
        Package pkg = packageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Package", id));

        if (request.getIsDefault()) {
            packageRepository.findByIsDefaultTrue().ifPresent(existing -> {
                if (!existing.getId().equals(id)) {
                    // toBuilder() keeps the demoted box's dimensions (PLAN 2609_38 D7).
                    packageRepository.save(existing.toBuilder().isDefault(false).build());
                }
            });
        }

        // toBuilder() carries every untouched column forward; only the request fields are overwritten.
        Package updated = pkg.toBuilder()
            .type(request.getType())
            .cost(request.getCost())
            .effectiveDate(request.getEffectiveDate())
            .isDefault(request.getIsDefault())
            .widthCm(request.getWidthCm())
            .lengthCm(request.getLengthCm())
            .heightCm(request.getHeightCm())
            .build();

        Package saved = packageRepository.save(updated);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deletePackage(Long id) {
        Package pkg = packageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Package", id));
        packageRepository.delete(pkg);
    }

    private PackageResponse toResponse(Package pkg) {
        return PackageResponse.builder()
            .id(pkg.getId())
            .type(pkg.getType())
            .cost(pkg.getCost())
            .effectiveDate(pkg.getEffectiveDate())
            .isDefault(pkg.getIsDefault())
            .widthCm(pkg.getWidthCm())
            .lengthCm(pkg.getLengthCm())
            .heightCm(pkg.getHeightCm())
            .build();
    }
}
