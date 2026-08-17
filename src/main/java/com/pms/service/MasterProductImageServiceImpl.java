package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.dto.response.MasterProductImageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementation of {@link MasterProductImageService} (FEATURE_2608_06 / Step 2-1).
 *
 * <p>Master ownership is enforced with {@code MasterProductRepository.findScopedById} (tenant-filtered →
 * cross-tenant/absent gives 404). {@code MasterProductImage} has no {@code @TenantId}, so the builder never
 * sets a tenant (isolation flows through the parent master).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterProductImageServiceImpl implements MasterProductImageService {

    private static final String STORAGE_CATEGORY = "master-detail";

    private final MasterProductImageRepository imageRepository;
    private final MasterProductRepository masterProductRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;

    @Override
    @Transactional
    public MasterProductImageResponse upload(Long masterId, String zoneId, MultipartFile file) {
        MasterProduct master = requireScopedMaster(masterId);
        imageValidator.validate(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("업로드 파일을 읽을 수 없습니다", e);
        }
        String url = imageStorageService.uploadBytes(
                bytes, STORAGE_CATEGORY,
                "master_" + masterId + "_" + zoneId + "_" + System.currentTimeMillis() + ".jpg",
                file.getContentType());

        // Next position = max(sortOrder)+1. Do NOT use size() — a delete leaves a gap and size() would
        // collide with an existing sortOrder.
        int nextOrder = imageRepository.findByMasterProductIdAndZoneId(masterId, zoneId).stream()
                .mapToInt(MasterProductImage::getSortOrder)
                .max()
                .orElse(-1) + 1;

        MasterProductImage saved = imageRepository.save(MasterProductImage.builder()
                .masterProduct(master)
                .zoneId(zoneId)
                .sortOrder(nextOrder)
                .imageUrl(url)
                .build());
        return mapToResponse(saved);
    }

    @Override
    public List<MasterProductImageResponse> list(Long masterId) {
        requireScopedMaster(masterId);
        return imageRepository.findByMasterProductIdOrderByZoneIdAscSortOrderAsc(masterId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<MasterProductImageResponse> reorder(Long masterId, String zoneId, List<Long> imageIds) {
        requireScopedMaster(masterId);
        List<MasterProductImage> zoneImages = imageRepository.findByMasterProductIdAndZoneId(masterId, zoneId);

        // Reject duplicates first: [1,2,2] vs zone {1,2} would pass a set-equality check yet assign a
        // duplicate sortOrder — the size comparison catches it before the set comparison.
        Set<Long> requested = new HashSet<>(imageIds);
        Set<Long> existing = new HashSet<>(zoneImages.stream().map(MasterProductImage::getId).toList());
        if (imageIds.size() != requested.size() || !requested.equals(existing)) {
            throw new IllegalArgumentException("이미지 목록 불일치");
        }

        java.util.Map<Long, MasterProductImage> byId = new java.util.HashMap<>();
        zoneImages.forEach(img -> byId.put(img.getId(), img));
        for (int i = 0; i < imageIds.size(); i++) {
            MasterProductImage img = byId.get(imageIds.get(i));
            imageRepository.save(img.toBuilder().sortOrder(i).build());
        }
        return imageRepository.findByMasterProductIdAndZoneIdOrderBySortOrderAsc(masterId, zoneId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long masterId, Long imageId) {
        requireScopedMaster(masterId);
        MasterProductImage image = imageRepository.findById(imageId)
                .filter(img -> img.getMasterProduct().getId().equals(masterId))
                .orElseThrow(() -> new ResourceNotFoundException("MasterProductImage", imageId));
        try {
            imageStorageService.deleteImage(image.getImageUrl());
        } catch (Exception e) {
            log.warn("Failed to delete master image from storage (id={}): {}", imageId, e.getMessage());
        }
        imageRepository.delete(image);
    }

    /** Tenant-scoped fetch; a cross-tenant/absent id yields 404 (findScopedById is @TenantId-filtered). */
    private MasterProduct requireScopedMaster(Long masterId) {
        return masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));
    }

    private MasterProductImageResponse mapToResponse(MasterProductImage image) {
        return MasterProductImageResponse.builder()
                .id(image.getId())
                .zoneId(image.getZoneId())
                .sortOrder(image.getSortOrder())
                .imageUrl(image.getImageUrl())
                .build();
    }
}
