package com.pms.service;

import com.pms.domain.ImageOp;
import com.pms.domain.ProcessingPreset;
import com.pms.dto.request.ProcessingPresetRequest;
import com.pms.dto.response.ProcessingPresetResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProcessingPresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Image-processing preset CRUD (FEATURE_2608_08). Tenant isolation is automatic via {@code @TenantId} on
 * {@link ProcessingPreset} — no manual tenant conditions. Mirror of {@link DetailTemplateServiceImpl} but
 * simpler: no default flag, so no demote logic.
 *
 * <p>⚠️ Entity is immutable (no setters): updates rebuild via {@code toBuilder} (partial — null request
 * fields keep existing values).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessingPresetServiceImpl implements ProcessingPresetService {

    private static final String OVERLAY = "overlay";

    private final ProcessingPresetRepository processingPresetRepository;

    @Override
    public List<ProcessingPresetResponse> list() {
        return processingPresetRepository.findAllByOrderByIdDesc().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public ProcessingPresetResponse get(Long id) {
        return mapToResponse(findOrThrow(id));
    }

    @Override
    @Transactional
    public ProcessingPresetResponse create(ProcessingPresetRequest request) {
        validateOperations(request.getOperations());
        ProcessingPreset preset = ProcessingPreset.builder()
                .name(request.getName())
                .operations(request.getOperations() == null ? List.of() : request.getOperations())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .build();
        return mapToResponse(processingPresetRepository.save(preset));
    }

    @Override
    @Transactional
    public ProcessingPresetResponse update(Long id, ProcessingPresetRequest request) {
        ProcessingPreset existing = findOrThrow(id);
        validateOperations(request.getOperations()); // null → skip (keep existing)
        ProcessingPreset updated = existing.toBuilder()
                .name(request.getName() != null ? request.getName() : existing.getName())
                .operations(request.getOperations() != null ? request.getOperations() : existing.getOperations())
                .active(request.getActive() != null ? request.getActive() : existing.getActive())
                .build();
        return mapToResponse(processingPresetRepository.save(updated));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        processingPresetRepository.delete(findOrThrow(id));
    }

    /**
     * Op rules (→400 on violation). {@code null} = keep-existing (partial update) → skip. Only the
     * essential requirement is checked: a non-blank {@code type}, and for {@code overlay} a non-blank
     * {@code assetStorageKey}. anchor/opacity/scale/margin are normalized by the engine, so they are not
     * validated here (minimal validation, matching validateBlocks).
     */
    private void validateOperations(List<ImageOp> operations) {
        if (operations == null) {
            return;
        }
        for (ImageOp op : operations) {
            if (!StringUtils.hasText(op.getType())) {
                throw new IllegalArgumentException("연산에는 type이 필요합니다");
            }
            if (OVERLAY.equalsIgnoreCase(op.getType()) && !StringUtils.hasText(op.getAssetStorageKey())) {
                throw new IllegalArgumentException("오버레이 연산은 assetStorageKey가 필요합니다");
            }
        }
    }

    private ProcessingPreset findOrThrow(Long id) {
        return processingPresetRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessingPreset", id));
    }

    private ProcessingPresetResponse mapToResponse(ProcessingPreset preset) {
        return ProcessingPresetResponse.builder()
                .id(preset.getId())
                .name(preset.getName())
                .operations(preset.getOperations())
                .active(preset.getActive())
                .build();
    }
}
