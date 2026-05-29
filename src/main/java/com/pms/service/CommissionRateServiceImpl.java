package com.pms.service;

import com.pms.domain.Category;
import com.pms.domain.CommissionRate;
import com.pms.dto.request.CommissionRateRequest;
import com.pms.dto.response.CommissionRateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryRepository;
import com.pms.repository.CommissionRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Implementation of CommissionRateService with JPA persistence.
 *
 * Handles CRUD operations for commission rates with fallback logic support.
 * Transactional boundaries: readOnly=true on class, @Transactional on write methods.
 *
 * @see CommissionRateService
 * @see CommissionRateRepository
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommissionRateServiceImpl implements CommissionRateService {

    private final CommissionRateRepository commissionRateRepository;
    private final CategoryRepository categoryRepository;

    @Override
    @Transactional
    public CommissionRateResponse create(CommissionRateRequest request) {
        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", request.getCategoryId()));
        }

        CommissionRate commissionRate = CommissionRate.builder()
                .platform(request.getPlatform())
                .category(category)
                .rate(request.getRate())
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : false)
                .build();

        CommissionRate saved = commissionRateRepository.save(commissionRate);
        return mapToResponse(saved);
    }

    @Override
    public List<CommissionRateResponse> findAll() {
        return commissionRateRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public CommissionRateResponse findById(Long id) {
        CommissionRate commissionRate = commissionRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CommissionRate", id));
        return mapToResponse(commissionRate);
    }

    @Override
    @Transactional
    public CommissionRateResponse update(Long id, CommissionRateRequest request) {
        CommissionRate commissionRate = commissionRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CommissionRate", id));

        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", request.getCategoryId()));
        }

        CommissionRate updated = CommissionRate.builder()
                .id(commissionRate.getId())
                .platform(request.getPlatform())
                .category(category)
                .rate(request.getRate())
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : false)
                .build();

        CommissionRate saved = commissionRateRepository.save(updated);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        CommissionRate commissionRate = commissionRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CommissionRate", id));
        commissionRateRepository.delete(commissionRate);
    }

    @Override
    public BigDecimal findRate(String platform, Long categoryId) {
        // First query: platform + category_id match
        return commissionRateRepository.findByPlatformAndCategoryId(platform, categoryId)
                .map(CommissionRate::getRate)
                // Fallback: platform + category_id = null (platform default)
                .orElseGet(() -> commissionRateRepository.findByPlatformAndCategoryId(platform, null)
                        .map(CommissionRate::getRate)
                        .orElseThrow(() -> new IllegalArgumentException("No commission rate found for platform: " + platform)));
    }

    private CommissionRateResponse mapToResponse(CommissionRate commissionRate) {
        String categoryName = null;
        Long categoryId = null;

        if (commissionRate.getCategory() != null) {
            categoryName = commissionRate.getCategory().getName();
            categoryId = commissionRate.getCategory().getId();
        }

        return CommissionRateResponse.builder()
                .id(commissionRate.getId())
                .platform(commissionRate.getPlatform())
                .categoryId(categoryId)
                .rate(commissionRate.getRate())
                .isDefault(commissionRate.getIsDefault())
                .categoryName(categoryName)
                .build();
    }
}
