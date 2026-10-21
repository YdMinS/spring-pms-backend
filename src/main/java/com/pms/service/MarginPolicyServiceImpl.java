package com.pms.service;

import com.pms.domain.MarginPolicy;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.dto.request.MarginPolicyRequest;
import com.pms.dto.response.MarginPolicyResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarginPolicyRepository;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /** FEATURE_2610_26 / D59: an ESM preset needs the Auction target margin too. */
    static final String AUCTION_MARGIN_REQUIRED = "옥션 목표 마진율을 입력하세요";

    private final MarginPolicyRepository marginPolicyRepository;
    private final SellerRepository sellerRepository;

    @Override
    @Transactional
    public MarginPolicyResponse createMarginPolicy(MarginPolicyRequest request) {
        Platform platform = Platform.from(request.getPlatform());
        requireNoDuplicate(request.getSellerId(), platform, null);
        Seller seller = requireSeller(request.getSellerId());

        MarginPolicy policy = MarginPolicy.builder()
                .seller(seller)
                .platform(platform)
                .marginRate(request.getMarginRate())
                .auctionMarginRate(auctionMarginRate(platform, request))
                .displayDiscountRate(request.getDisplayDiscountRate())
                // 2609_39/D4: alert thresholds. null is a meaningful value here (= condition unused).
                .minMarginAmount(request.getMinMarginAmount())
                .minMarginRate(request.getMinMarginRate())
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
        Platform platform = Platform.from(request.getPlatform());
        requireNoDuplicate(request.getSellerId(), platform, id);
        Seller seller = requireSeller(request.getSellerId());

        MarginPolicy updated = existing.toBuilder()
                .seller(seller)
                .platform(platform)
                .marginRate(request.getMarginRate())
                // FEATURE_2610_26 / D59: written straight through like marginRate (required on ESM, null elsewhere).
                .auctionMarginRate(auctionMarginRate(platform, request))
                // null = keep existing (optional field convention).
                .displayDiscountRate(request.getDisplayDiscountRate() != null
                        ? request.getDisplayDiscountRate() : existing.getDisplayDiscountRate())
                // 2609_39/D4: written straight through — unlike displayDiscountRate, null does NOT mean
                // "keep": it means "stop alerting on this axis", and the user must be able to clear it.
                .minMarginAmount(request.getMinMarginAmount())
                .minMarginRate(request.getMinMarginRate())
                .build();
        return mapToResponse(marginPolicyRepository.save(updated));
    }

    @Override
    @Transactional
    public void deleteMarginPolicy(Long id) {
        marginPolicyRepository.delete(requirePolicy(id));
    }

    /** Reject a second preset for the same (seller, platform); {@code selfId} is excluded on update. */
    private void requireNoDuplicate(Long sellerId, Platform platform, Long selfId) {
        marginPolicyRepository.findBySellerIdAndPlatform(sellerId, platform)
                .filter(found -> !found.getId().equals(selfId))
                .ifPresent(found -> {
                    throw new IllegalArgumentException("이미 존재하는 마진 프리셋");
                });
    }

    /** D59: required on an ESM preset (400 when missing); every other platform stores null. */
    private static BigDecimal auctionMarginRate(Platform platform, MarginPolicyRequest request) {
        if (platform != Platform.ESM) {
            return null;
        }
        if (request.getAuctionMarginRate() == null) {
            throw new IllegalArgumentException(AUCTION_MARGIN_REQUIRED);
        }
        return request.getAuctionMarginRate();
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
                .platform(policy.getPlatform().name())
                .marginRate(policy.getMarginRate())
                .auctionMarginRate(policy.getAuctionMarginRate())
                .displayDiscountRate(policy.getDisplayDiscountRate())
                .minMarginAmount(policy.getMinMarginAmount())
                .minMarginRate(policy.getMinMarginRate())
                .build();
    }
}
