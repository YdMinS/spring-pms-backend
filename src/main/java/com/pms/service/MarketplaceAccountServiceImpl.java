package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailTemplateRepository;
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
    private final ThumbnailTemplateRepository thumbnailTemplateRepository;
    private final DetailTemplateRepository detailTemplateRepository;

    @Override
    @Transactional
    public MarketplaceAccountResponse create(MarketplaceAccountRequest req) {
        // secretKey is optional on update (blank keeps existing) but required on create
        if (!StringUtils.hasText(req.getSecretKey())) {
            throw new IllegalArgumentException("secretKey is required");
        }

        Seller seller = sellerRepository.findById(req.getSellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", req.getSellerId()));

        MarketplaceAccount account = MarketplaceAccount.builder()
                .seller(seller)
                .platform(req.getPlatform())
                .accountAlias(req.getAccountAlias())
                .vendorId(req.getVendorId())
                .vendorUserId(req.getVendorUserId())
                .accessKey(req.getAccessKey())
                .secretKey(req.getSecretKey())
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                // Channel template override (21): validate id when provided (404), else null = tenant default.
                .thumbnailTemplate(resolveThumbnailTemplate(req.getThumbnailTemplateId()))
                .detailTemplate(resolveDetailTemplate(req.getDetailTemplateId()))
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

        // Template override: null keeps existing (secretKey convention), a value re-validates (404) and replaces.
        ThumbnailTemplate thumbnailTemplate = req.getThumbnailTemplateId() != null
                ? resolveThumbnailTemplate(req.getThumbnailTemplateId())
                : existing.getThumbnailTemplate();
        DetailTemplate detailTemplate = req.getDetailTemplateId() != null
                ? resolveDetailTemplate(req.getDetailTemplateId())
                : existing.getDetailTemplate();

        MarketplaceAccount updated = existing.toBuilder()
                .seller(seller)
                .platform(req.getPlatform())
                .accountAlias(req.getAccountAlias())
                .vendorId(req.getVendorId())
                // vendorUserId: same semantics as vendorId/accessKey — request value directly replaces
                // (full overwrite, not the thumbnailTemplate null-keep pattern).
                .vendorUserId(req.getVendorUserId())
                .accessKey(req.getAccessKey())
                .secretKey(secretKey)
                .isActive(req.getIsActive() != null ? req.getIsActive() : existing.getIsActive())
                .thumbnailTemplate(thumbnailTemplate)
                .detailTemplate(detailTemplate)
                .build();

        return mapToResponse(repository.save(updated));
    }

    @Override
    @Transactional
    public MarketplaceAccountResponse updateRegistrationNameSuffix(Long id, OptionCheckSuffixRequest req) {
        MarketplaceAccount existing = findOrThrow(id);
        MarketplaceAccount updated = existing.toBuilder()
                .optionCheckSuffixEnabled(req.getEnabled())
                .optionCheckSuffix(normalizeSuffix(req.getSuffix()))
                .build();
        return mapToResponse(repository.save(updated));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        repository.delete(findOrThrow(id));
    }

    /** blank → null (inherit); else trimmed. Shared normalization for the 69 suffix text. */
    private static String normalizeSuffix(String suffix) {
        return StringUtils.hasText(suffix) ? suffix.trim() : null;
    }

    private MarketplaceAccount findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", id));
    }

    /** Load the assigned thumbnail template (404 when the id does not exist), or null when unspecified. */
    private ThumbnailTemplate resolveThumbnailTemplate(Long templateId) {
        if (templateId == null) {
            return null;
        }
        return thumbnailTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("ThumbnailTemplate", templateId));
    }

    /** Load the assigned detail template (404 when the id does not exist), or null when unspecified. */
    private DetailTemplate resolveDetailTemplate(Long templateId) {
        if (templateId == null) {
            return null;
        }
        return detailTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("DetailTemplate", templateId));
    }

    private MarketplaceAccountResponse mapToResponse(MarketplaceAccount a) {
        // secretKey 제외하고 매핑 (민감 자격증명 노출 금지)
        return MarketplaceAccountResponse.builder()
                .id(a.getId())
                .sellerId(a.getSeller().getId())
                .platform(a.getPlatform())
                .accountAlias(a.getAccountAlias())
                .vendorId(a.getVendorId())
                .vendorUserId(a.getVendorUserId())
                .accessKey(a.getAccessKey())
                .isActive(a.getIsActive())
                // id only (LAZY getId reads the FK without a query); template display name resolved on the front.
                .thumbnailTemplateId(a.getThumbnailTemplate() != null ? a.getThumbnailTemplate().getId() : null)
                .detailTemplateId(a.getDetailTemplate() != null ? a.getDetailTemplate().getId() : null)
                .optionCheckSuffixEnabled(a.getOptionCheckSuffixEnabled())
                .optionCheckSuffix(a.getOptionCheckSuffix())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
