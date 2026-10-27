package com.pms.service;

import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.ProductImage;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.response.MasterProductImageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.ThumbnailTemplateRepository;
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
    private final ProductImageRepository productImageRepository;
    private final ProductRepository productRepository;
    private final ProductImageUrlResolver productImageUrlResolver;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;
    /** Existence check of a thumbnail template image group zone (FEATURE_2610_33 D13). */
    private final ThumbnailTemplateRepository thumbnailTemplateRepository;

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
                .build()); // zoneId + productImage left null — edited pool asset, mapping is separate
        return mapToResponse(saved, List.of());
    }

    @Override
    @Transactional
    public List<MasterProductImageResponse> importProductImages(Long masterId, List<Long> productImageIds) {
        MasterProduct master = requireScopedMaster(masterId);

        // Next position = max(sortOrder)+1 (do NOT use size() — a delete leaves a gap). Reused for the batch.
        int nextOrder = imageRepository.findByMasterProductIdOrderBySortOrderAsc(masterId).stream()
                .mapToInt(MasterProductImage::getSortOrder)
                .max()
                .orElse(-1) + 1;

        List<MasterProductImage> toSave = new ArrayList<>();
        for (Long productImageId : productImageIds) {
            ProductImage slot = productImageRepository.findById(productImageId)
                    .orElseThrow(() -> new ResourceNotFoundException("ProductImage", productImageId));
            // Tenant ownership: the slot's owning product must be visible in this tenant (findScopedById is
            // @TenantId-filtered, unlike a PK findById) — a cross-tenant slot yields an empty result → 400.
            productRepository.findScopedById(slot.getProduct().getId())
                    .orElseThrow(() -> new IllegalArgumentException("다른 테넌트의 상품 이미지는 가져올 수 없습니다"));
            // Reference entry: live-links the slot, imageUrl stays null (URL resolved live from productImage).
            toSave.add(MasterProductImage.builder()
                    .masterProduct(master)
                    .productImage(slot)
                    .sortOrder(nextOrder++)
                    .build());
        }
        imageRepository.saveAll(toSave); // single call
        return listPool(masterId);
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
        if (zoneId.startsWith(ThumbnailTemplate.IMAGE_GROUP_KEY_PREFIX)) {
            // FEATURE_2610_33 D13 · D24: a thumbnail template image group — the template must exist in this
            // tenant, then at most one photo. Every other zone id is accepted unchecked as before.
            requireThumbnailTemplateGroup(zoneId);
            if (imageIds.size() > 1) {
                throw new IllegalArgumentException("썸네일 템플릿 그룹에는 사진을 한 장만 넣을 수 있습니다");
            }
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
        // 🔴 Flush the deletes BEFORE inserting. The derived delete only queues removals, while the inserts
        // below fire immediately (IDENTITY ids) — without this the re-inserted rows hit uq_miza_image_zone
        // (024) and the whole call 500s. Symptom: a zone accepts its first image but never a second one.
        assignmentRepository.flush();
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
        // Same flush contract as setZoneImages: re-selecting the image that is already the cover would
        // otherwise insert a duplicate (image, __source__) row before the delete reaches the database.
        assignmentRepository.flush();
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

    /**
     * 400 「썸네일 템플릿 없음」 unless {@code zoneId} is exactly {@link ThumbnailTemplate#imageGroupKey} of a
     * template in the current tenant (FEATURE_2610_33 D13). A malformed id after the prefix is the same 400.
     */
    private void requireThumbnailTemplateGroup(String zoneId) {
        String rest = zoneId.substring(ThumbnailTemplate.IMAGE_GROUP_KEY_PREFIX.length());
        Long templateId = null;
        if (rest.endsWith("__")) {
            try {
                templateId = Long.valueOf(rest.substring(0, rest.length() - 2));
            } catch (NumberFormatException ignored) {
                templateId = null;
            }
        }
        if (templateId == null
                || !ThumbnailTemplate.imageGroupKey(templateId).equals(zoneId)
                || !thumbnailTemplateRepository.existsScopedById(templateId)) {
            throw new IllegalArgumentException("썸네일 템플릿 없음");
        }
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
        // Effective URL resolved through the single helper: a reference entry serves the live product URL,
        // an edited entry serves its own imageUrl (LAZY productImage read is safe — inside the service tx).
        return MasterProductImageResponse.builder()
                .id(image.getId())
                .sortOrder(image.getSortOrder())
                .imageUrl(productImageUrlResolver.resolve(image))
                .productImageId(image.getProductImage() != null ? image.getProductImage().getId() : null)
                .assignedZones(assignedZones)
                .isSource(isSource)
                .build();
    }
}
