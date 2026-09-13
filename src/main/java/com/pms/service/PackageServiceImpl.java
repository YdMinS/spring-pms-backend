package com.pms.service;

import com.pms.domain.BoxKind;
import com.pms.domain.Package;
import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.PackageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

/**
 * Service implementation for Package management.
 *
 * Handles all business logic including:
 * - isDefault uniqueness maintenance
 * - box-kind rules (FEATURE_2609_40 / PLAN D20 · D21): a RECYCLED box may cost 0 and can never be the
 *   default box, a PURCHASED box must cost more than 0. This is the ONLY validation that branches on kind
 *   and it lives here because a field-level annotation cannot see another field.
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

    /** Storage bucket for box photos (FEATURE_2609_40 / PLAN D26). */
    private static final String IMAGE_CATEGORY = "packages";

    private final PackageRepository packageRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;

    @Override
    @Transactional
    public PackageResponse createPackage(PackageRequest request) {
        validateBoxKindRules(request);
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
            .boxKind(kindOf(request))
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
    public List<PackageResponse> getPackages(BoxKind boxKind) {
        // No filter = every kind (PLAN 2609_40 D21). Hiding recycled boxes by default would blind the
        // packing screen, which is exactly where they belong; the pricing dropdowns ask for PURCHASED.
        List<Package> packages = boxKind == null
            ? packageRepository.findAll()
            : packageRepository.findByBoxKind(boxKind);
        return packages.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public PackageResponse updatePackage(Long id, PackageRequest request) {
        Package pkg = packageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Package", id));

        validateBoxKindRules(request);

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
            .boxKind(kindOf(request))
            .build(); // imageUrl is deliberately untouched — the upload endpoint owns it

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

    @Override
    @Transactional
    public PackageResponse uploadImage(Long id, MultipartFile file) {
        Package pkg = packageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Package", id));

        imageValidator.validate(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("업로드 파일을 읽을 수 없습니다", e);
        }
        String contentType = StringUtils.hasText(file.getContentType()) ? file.getContentType() : "image/jpeg";
        // Shared storage seam only (FEATURE_2608_04) — no storage path of our own.
        String url = imageStorageService.uploadBytes(
            bytes, IMAGE_CATEGORY,
            "package_" + id + "_" + System.currentTimeMillis() + ".jpg", contentType);

        // The previous object is NOT deleted: the same stored value may be referenced elsewhere.
        Package saved = packageRepository.save(pkg.toBuilder().imageUrl(url).build());
        return toResponse(saved);
    }

    /** Null means "not specified" and keeps pre-feature clients creating bought boxes (PLAN 2609_40 D20). */
    private BoxKind kindOf(PackageRequest request) {
        return request.getBoxKind() == null ? BoxKind.PURCHASED : request.getBoxKind();
    }

    /**
     * The only validation that branches on box kind (PLAN 2609_40 D20 · D21).
     *
     * <p>🔴 A recycled box may cost 0 — refusing that would make recycled boxes impossible to register.
     * 🔴 A recycled box can never be the default box: the default box is what the selling-price calculation
     * falls back to, and a zero-cost box there prices goods off nothing.</p>
     */
    private void validateBoxKindRules(PackageRequest request) {
        if (kindOf(request) == BoxKind.RECYCLED) {
            if (Boolean.TRUE.equals(request.getIsDefault())) {
                throw new IllegalArgumentException("재활용 상자는 기본 상자로 지정할 수 없습니다");
            }
            return;
        }
        if (request.getCost() == null || request.getCost().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("구매 상자의 상자비는 0보다 커야 합니다");
        }
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
            .boxKind(pkg.getBoxKind())
            .imageUrl(pkg.getImageUrl())
            .build();
    }
}
