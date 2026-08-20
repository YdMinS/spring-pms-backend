package com.pms.service;

import com.pms.domain.DetailBlock;
import com.pms.domain.DetailTemplate;
import com.pms.domain.ProcessingPreset;
import com.pms.dto.request.DetailTemplateRequest;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.ProcessingPresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * Detail-page template CRUD (FEATURE_2608_06 / 17). Tenant isolation is automatic via {@code @TenantId}
 * on {@link DetailTemplate} — no manual tenant conditions. Single default per tenant enforced with the
 * CarrierRate/ThumbnailTemplate pattern ({@link #demoteExistingDefault}).
 *
 * <p>⚠️ Entity is immutable (no setters): updates rebuild via {@code toBuilder} (partial — null request
 * fields keep existing values).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DetailTemplateServiceImpl implements DetailTemplateService {

    private static final Set<String> KNOWN_TYPES = Set.of("text", "imageZone", "asset", "spacer");

    private final DetailTemplateRepository detailTemplateRepository;
    private final ProcessingPresetRepository processingPresetRepository;

    @Override
    public List<DetailTemplateResponse> list() {
        return detailTemplateRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public DetailTemplateResponse get(Long id) {
        return mapToResponse(findOrThrow(id));
    }

    @Override
    @Transactional
    public DetailTemplateResponse create(DetailTemplateRequest request) {
        validateBlocks(request.getBlocks());
        boolean makeDefault = Boolean.TRUE.equals(request.getIsDefault());
        if (makeDefault) {
            demoteExistingDefault(null);
        }
        DetailTemplate template = DetailTemplate.builder()
                .name(request.getName())
                .blocks(request.getBlocks() == null ? List.of() : request.getBlocks())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .isDefault(makeDefault)
                .imageProcessingPreset(resolvePreset(request.getImageProcessingPresetId()))
                .build();
        return mapToResponse(detailTemplateRepository.save(template));
    }

    @Override
    @Transactional
    public DetailTemplateResponse update(Long id, DetailTemplateRequest request) {
        DetailTemplate existing = findOrThrow(id);
        validateBlocks(request.getBlocks()); // null → skip (keep existing)
        boolean makeDefault = Boolean.TRUE.equals(request.getIsDefault());
        if (makeDefault) {
            demoteExistingDefault(id);
        }
        DetailTemplate updated = existing.toBuilder()
                .name(request.getName() != null ? request.getName() : existing.getName())
                .blocks(request.getBlocks() != null ? request.getBlocks() : existing.getBlocks())
                .active(request.getActive() != null ? request.getActive() : existing.getActive())
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : existing.getIsDefault())
                // PATCH: null preset id keeps the existing reference (secretKey convention); a value replaces it.
                .imageProcessingPreset(request.getImageProcessingPresetId() != null
                        ? resolvePreset(request.getImageProcessingPresetId())
                        : existing.getImageProcessingPreset())
                .build();
        return mapToResponse(detailTemplateRepository.save(updated));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        detailTemplateRepository.delete(findOrThrow(id));
    }

    /**
     * Enforce one default per tenant (CarrierRate pattern): demote the current default before promoting a
     * new one. {@code keepId} = the template being promoted (skip if it is already the default).
     */
    private void demoteExistingDefault(Long keepId) {
        detailTemplateRepository.findByIsDefaultTrueAndActiveTrue().ifPresent(current -> {
            if (!current.getId().equals(keepId)) {
                detailTemplateRepository.save(current.toBuilder().isDefault(false).build());
            }
        });
    }

    /**
     * Block rules (→400 on violation). {@code null} = keep-existing (partial update) → skip. Only the
     * essential per-type requirement is checked; widthPercent/align/defaultValue are normalized by the
     * renderer (fallback/skip), so they are not validated here (ThumbnailTemplate.validateFields minimalism).
     */
    private void validateBlocks(List<DetailBlock> blocks) {
        if (blocks == null) {
            return;
        }
        for (DetailBlock block : blocks) {
            String type = block.getType();
            if (!KNOWN_TYPES.contains(type)) {
                throw new IllegalArgumentException("알 수 없는 블록 타입: " + type);
            }
            switch (type) {
                case "text" -> {
                    if (!StringUtils.hasText(block.getBind())) {
                        throw new IllegalArgumentException("텍스트 블록은 bind(필드키)가 필요합니다");
                    }
                }
                case "imageZone" -> {
                    if (!StringUtils.hasText(block.getBind())) {
                        throw new IllegalArgumentException("이미지존 블록은 zoneId가 필요합니다");
                    }
                }
                case "asset" -> {
                    if (!StringUtils.hasText(block.getSrc())) {
                        throw new IllegalArgumentException("에셋 블록은 이미지(src)가 필요합니다");
                    }
                }
                case "spacer" -> {
                    if (block.getHeightPx() == null || block.getHeightPx() < 1) {
                        throw new IllegalArgumentException("여백 블록은 높이(px)가 필요합니다");
                    }
                }
                default -> { /* unreachable: guarded by KNOWN_TYPES above */ }
            }
        }
    }

    private DetailTemplate findOrThrow(Long id) {
        return detailTemplateRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DetailTemplate", id));
    }

    /** Resolve an optional preset reference: null id → null, else tenant-scoped fetch (404 if absent). */
    private ProcessingPreset resolvePreset(Long presetId) {
        if (presetId == null) {
            return null;
        }
        return processingPresetRepository.findScopedById(presetId)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessingPreset", presetId));
    }

    private DetailTemplateResponse mapToResponse(DetailTemplate template) {
        return DetailTemplateResponse.builder()
                .id(template.getId())
                .name(template.getName())
                .blocks(template.getBlocks())
                .active(template.getActive())
                .isDefault(template.getIsDefault())
                .imageProcessingPresetId(template.getImageProcessingPreset() == null
                        ? null : template.getImageProcessingPreset().getId())
                .build();
    }
}
