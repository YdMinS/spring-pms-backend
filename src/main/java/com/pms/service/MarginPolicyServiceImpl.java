package com.pms.service;

import com.pms.domain.MarginPolicy;
import com.pms.domain.Seller;
import com.pms.dto.request.MarginPolicyRequest;
import com.pms.dto.response.MarginPolicyResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Margin preset CRUD (FEATURE_2608_06 / 3a) — mirrors the CarrierRate service shape.
 *
 * <p>Business rule: (seller, platform) is unique per tenant. create/update reject a duplicate with a
 * 400 ({@code IllegalArgumentException}); update excludes the record itself. Seller must exist (404).
 * Tenant scoping is automatic via {@code @TenantId}.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarginPolicyServiceImpl implements MarginPolicyService {

    private final MarginPolicyRepository marginPolicyRepository;
    private final SellerRepository sellerRepository;

    @Override
    @Transactional
    public MarginPolicyResponse createMarginPolicy(MarginPolicyRequest request) {
        requireNoDuplicate(request.getSellerId(), request.getPlatform(), null);
        Seller seller = requireSeller(request.getSellerId());

        MarginPolicy policy = MarginPolicy.builder()
                .seller(seller)
                .platform(request.getPlatform())
                .marginRate(request.getMarginRate())
                .build();
        return mapToResponse(marginPolicyRepository.save(policy));
    }

    @Override
    public MarginPolicyResponse getMarginPolicy(Long id) {
        return mapToResponse(requirePolicy(id));
    }

    @Override
    public List<MarginPolicyResponse> getMarginPolicies() {
        return marginPolicyRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public MarginPolicyResponse updateMarginPolicy(Long id, MarginPolicyRequest request) {
        MarginPolicy existing = requirePolicy(id);
        requireNoDuplicate(request.getSellerId(), request.getPlatform(), id);
        Seller seller = requireSeller(request.getSellerId());

        MarginPolicy updated = existing.toBuilder()
                .seller(seller)
                .platform(request.getPlatform())
                .marginRate(request.getMarginRate())
                .build();
        return mapToResponse(marginPolicyRepository.save(updated));
    }

    @Override
    @Transactional
    public void deleteMarginPolicy(Long id) {
        marginPolicyRepository.delete(requirePolicy(id));
    }

    /** Reject a second preset for the same (seller, platform); {@code selfId} is excluded on update. */
    private void requireNoDuplicate(Long sellerId, String platform, Long selfId) {
        marginPolicyRepository.findBySellerIdAndPlatform(sellerId, platform)
                .filter(found -> !found.getId().equals(selfId))
                .ifPresent(found -> {
                    throw new IllegalArgumentException("이미 존재하는 마진 프리셋");
                });
    }

    private Seller requireSeller(Long sellerId) {
        return sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller", sellerId));
    }

    private MarginPolicy requirePolicy(Long id) {
        return marginPolicyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MarginPolicy", id));
    }

    private MarginPolicyResponse mapToResponse(MarginPolicy policy) {
        // seller is LAZY → resolved within @Transactional scope.
        return MarginPolicyResponse.builder()
                .id(policy.getId())
                .sellerId(policy.getSeller().getId())
                .sellerName(policy.getSeller().getSellerName())
                .platform(policy.getPlatform())
                .marginRate(policy.getMarginRate())
                .build();
    }
}
