package com.pms.service;

import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.dto.response.MasterProductImageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link MasterProductImageService} (FEATURE_2608_06 / 37).
 *
 * <p>Master ownership is enforced with {@code MasterProductRepository.findScopedById} (tenant-filtered →
 * cross-tenant/absent gives 404). Neither {@code MasterProductImage} nor {@code MasterImageZoneAssignment}
 * has a {@code @TenantId}, so the builders never set a tenant (isolation flows through the parent master).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterProductImageServiceImpl implements MasterProductImageService {

    private static final String STORAGE_CATEGORY = "master-pool";

    private final MasterProductImageRepository imageRepository;
    private final MasterImageZoneAssignmentRepository assignmentRepository;
    private final MasterProductRepository masterProductRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;

    @Override
    @Transactional
    public MasterProductImageResponse uploadToPool(Long masterId, MultipartFile file) {
        MasterProduct master = requireScopedMaster(masterId);
        imageValidator.validate(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("업로드 파일을 읽을 수 없습니다", e);
        }
        String contentType = StringUtils.hasText(file.getContentType()) ? file.getContentType() : "image/jpeg";
        String url = imageStorageService.uploadBytes(
                bytes, STORAGE_CATEGORY,
                "master_" + masterId + "_" + System.currentTimeMillis() + ".jpg", contentType);

        // Next position = max(sortOrder)+1. Do NOT use size() — a delete leaves a gap and size() would
        // collide with an existing sortOrder.
        int nextOrder = imageRepository.findByMasterProductIdOrderBySortOrderAsc(masterId).stream()
                .mapToInt(MasterProductImage::getSortOrder)
                .max()
                .orElse(-1) + 1;

        MasterProductImage saved = imageRepository.save(MasterProductImage.builder()
                .masterProduct(master)
                .sortOrder(nextOrder)
                .imageUrl(url)
                .build()); // zoneId left null — pool asset, mapping is separate
        return mapToResponse(saved, List.of());
    }

    @Override
    public List<MasterProductImageResponse> listPool(Long masterId) {
        requireScopedMaster(masterId);
        Map<Long, List<MasterImageZoneAssignment>> byImageId = assignmentsByImageId(masterId);
        return imageRepository.findByMasterProductIdOrderBySortOrderAsc(masterId).stream()
                .map(img -> mapToResponse(img, byImageId.getOrDefault(img.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional
    public List<MasterProductImageResponse> setZoneImages(Long masterId, String zoneId, List<Long> imageIds) {
        requireScopedMaster(masterId);
        if (MasterImageZoneAssignment.SOURCE_ZONE.equals(zoneId)) {
            throw new IllegalArgumentException("대표사진은 source-image 로 설정하세요");
        }
        // Reject duplicates first (size vs distinct), then require every id to belong to this master's pool.
        Set<Long> distinct = new LinkedHashSet<>(imageIds);
        if (distinct.size() != imageIds.size()) {
            throw new IllegalArgumentException("이미지 목록 불일치");
        }
        Map<Long, MasterProductImage> pool = new HashMap<>();
        imageRepository.findByMasterProductIdOrderBySortOrderAsc(masterId)
                .forEach(img -> pool.put(img.getId(), img));
        if (!pool.keySet().containsAll(imageIds)) {
            throw new IllegalArgumentException("이미지 목록 불일치");
        }

        assignmentRepository.deleteByImage_MasterProductIdAndZoneId(masterId, zoneId);
        List<MasterImageZoneAssignment> toSave = new ArrayList<>();
        for (int i = 0; i < imageIds.size(); i++) {
            toSave.add(MasterImageZoneAssignment.builder()
                    .image(pool.get(imageIds.get(i)))
                    .zoneId(zoneId)
                    .sortOrder(i)
                    .build());
        }
        assignmentRepository.saveAll(toSave);
        return mapZone(masterId, zoneId);
    }

    @Override
    @Transactional
    public MasterProductImageResponse setSourceImage(Long masterId, Long imageId) {
        requireScopedMaster(masterId);
        // Single-cover invariant: always clear the existing __source__ mapping first (delete-then-insert).
        assignmentRepository.deleteByImage_MasterProductIdAndZoneId(
                masterId, MasterImageZoneAssignment.SOURCE_ZONE);
        if (imageId == null) {
            return null; // cover cleared → reverts to BOM derivation
        }
        MasterProductImage image = imageRepository.findById(imageId)
                .filter(img -> img.getMasterProduct().getId().equals(masterId))
                .orElseThrow(() -> new IllegalArgumentException("이미지 목록 불일치"));
        assignmentRepository.saveAll(List.of(MasterImageZoneAssignment.builder()
                .image(image)
                .zoneId(MasterImageZoneAssignment.SOURCE_ZONE)
                .sortOrder(0)
                .build()));
        return mapToResponse(image, assignmentsByImageId(masterId).getOrDefault(imageId, List.of()));
    }

    @Override
    @Transactional
    public void removeFromPool(Long masterId, Long imageId) {
        requireScopedMaster(masterId);
        MasterProductImage image = imageRepository.findById(imageId)
                .filter(img -> img.getMasterProduct().getId().equals(masterId))
                .orElseThrow(() -> new ResourceNotFoundException("MasterProductImage", imageId));
        // Order: (1) clear mappings, (2) delete the DB row — both committed with the transaction; (3) S3 delete
        // is best-effort (failure is swallowed/logged, DB cleanup still commits).
        assignmentRepository.deleteByImageId(imageId);
        imageRepository.delete(image);
        try {
            imageStorageService.deleteImage(image.getImageUrl());
        } catch (Exception e) {
            log.warn("Failed to delete master pool image from storage (id={}): {}", imageId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Tenant-scoped fetch; a cross-tenant/absent id yields 404 (findScopedById is @TenantId-filtered). */
    private MasterProduct requireScopedMaster(Long masterId) {
        return masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));
    }

    /** One master's mappings grouped by image id (single query). */
    private Map<Long, List<MasterImageZoneAssignment>> assignmentsByImageId(Long masterId) {
        Map<Long, List<MasterImageZoneAssignment>> byImageId = new HashMap<>();
        for (MasterImageZoneAssignment a : assignmentRepository
                .findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(masterId)) {
            byImageId.computeIfAbsent(a.getImage().getId(), k -> new ArrayList<>()).add(a);
        }
        return byImageId;
    }

    /** The images mapped to one zone, in render order, each with its full mapping state. */
    private List<MasterProductImageResponse> mapZone(Long masterId, String zoneId) {
        Map<Long, List<MasterImageZoneAssignment>> byImageId = assignmentsByImageId(masterId);
        return assignmentRepository
                .findByImage_MasterProductIdAndZoneIdOrderBySortOrderAsc(masterId, zoneId).stream()
                .map(a -> mapToResponse(a.getImage(),
                        byImageId.getOrDefault(a.getImage().getId(), List.of())))
                .toList();
    }

    private MasterProductImageResponse mapToResponse(
            MasterProductImage image, List<MasterImageZoneAssignment> assignments) {
        List<String> assignedZones = assignments.stream()
                .map(MasterImageZoneAssignment::getZoneId)
                .filter(z -> !MasterImageZoneAssignment.SOURCE_ZONE.equals(z))
                .toList();
        boolean isSource = assignments.stream()
                .anyMatch(a -> MasterImageZoneAssignment.SOURCE_ZONE.equals(a.getZoneId()));
        return MasterProductImageResponse.builder()
                .id(image.getId())
                .sortOrder(image.getSortOrder())
                .imageUrl(image.getImageUrl())
                .assignedZones(assignedZones)
                .isSource(isSource)
                .build();
    }
}
