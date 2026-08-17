package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.DetailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only implementation of {@link DetailTemplateService} (FEATURE_2608_06 / Step 2-1).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DetailTemplateServiceImpl implements DetailTemplateService {

    private final DetailTemplateRepository detailTemplateRepository;

    @Override
    public List<DetailTemplateResponse> list() {
        return detailTemplateRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public DetailTemplateResponse get(Long id) {
        DetailTemplate template = detailTemplateRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DetailTemplate", id));
        return mapToResponse(template);
    }

    private DetailTemplateResponse mapToResponse(DetailTemplate template) {
        return DetailTemplateResponse.builder()
                .id(template.getId())
                .name(template.getName())
                .blocks(template.getBlocks())
                .active(template.getActive())
                .isDefault(template.getIsDefault())
                .build();
    }
}
