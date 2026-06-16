package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * {@link MarketplaceAccountService} 구현. JPA 영속화 + secretKey 조건부 교체 로직.
 *
 * 트랜잭션 경계: 클래스 readOnly=true, 쓰기 메서드에 @Transactional.
 *
 * @see MarketplaceAccountService
 * @see com.pms.security.crypto.AesAttributeConverter
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketplaceAccountServiceImpl implements MarketplaceAccountService {

    private final MarketplaceAccountRepository repository;
    private final SellerRepository sellerRepository;

    @Override
    @Transactional
    public MarketplaceAccountResponse create(MarketplaceAccountRequest req) {
        Seller seller = sellerRepository.findById(req.getSellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", req.getSellerId()));

        MarketplaceAccount account = MarketplaceAccount.builder()
                .seller(seller)
                .platform(req.getPlatform())
                .accountAlias(req.getAccountAlias())
                .vendorId(req.getVendorId())
                .accessKey(req.getAccessKey())
                .secretKey(req.getSecretKey())
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();

        return mapToResponse(repository.save(account));
    }

    @Override
    public MarketplaceAccountResponse get(Long id) {
        return mapToResponse(findOrThrow(id));
    }

    @Override
    public List<MarketplaceAccountResponse> list(Long sellerId) {
        List<MarketplaceAccount> accounts = (sellerId != null)
                ? repository.findBySeller_Id(sellerId)
                : repository.findAll();
        return accounts.stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public MarketplaceAccountResponse update(Long id, MarketplaceAccountRequest req) {
        MarketplaceAccount existing = findOrThrow(id);

        Seller seller = sellerRepository.findById(req.getSellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", req.getSellerId()));

        // secretKey: 요청에 값이 있으면 교체, 빈/누락이면 기존값 유지(재암호화 회피)
        String secretKey = StringUtils.hasText(req.getSecretKey())
                ? req.getSecretKey()
                : existing.getSecretKey();

        MarketplaceAccount updated = existing.toBuilder()
                .seller(seller)
                .platform(req.getPlatform())
                .accountAlias(req.getAccountAlias())
                .vendorId(req.getVendorId())
                .accessKey(req.getAccessKey())
                .secretKey(secretKey)
                .isActive(req.getIsActive() != null ? req.getIsActive() : existing.getIsActive())
                .build();

        return mapToResponse(repository.save(updated));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        repository.delete(findOrThrow(id));
    }

    private MarketplaceAccount findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", id));
    }

    private MarketplaceAccountResponse mapToResponse(MarketplaceAccount a) {
        // secretKey 제외하고 매핑 (민감 자격증명 노출 금지)
        return MarketplaceAccountResponse.builder()
                .id(a.getId())
                .sellerId(a.getSeller().getId())
                .platform(a.getPlatform())
                .accountAlias(a.getAccountAlias())
                .vendorId(a.getVendorId())
                .accessKey(a.getAccessKey())
                .isActive(a.getIsActive())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
