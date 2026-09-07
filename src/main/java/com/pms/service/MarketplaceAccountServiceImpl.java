package com.pms.service;

import com.pms.domain.CoupangAccountCredential;
import com.pms.domain.DetailTemplate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CoupangAccountCredentialRepository;
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
 * <p>🔴 계정(core)과 쿠팡 자격증명({@link CoupangAccountCredential})을 <b>한 트랜잭션</b>에서 나눠 저장한다
 * (FEATURE_2609_26 / PLAN D15). 요청·응답 DTO 구조는 예전 그대로(평평한 vendorId/accessKey/secretKey)라
 * 웹·모바일은 무변경이다.
 *
 * <p>⚠️ 수정은 기존 자격증명 행을 {@code toBuilder()} 로 갱신한다 — 새 인스턴스를 저장하면
 * {@code uq_coupang_cred_account} 위반이다.
 *
 * @see MarketplaceAccountService
 * @see com.pms.security.crypto.AesAttributeConverter
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketplaceAccountServiceImpl implements MarketplaceAccountService {

    private final MarketplaceAccountRepository repository;
    private final CoupangAccountCredentialRepository credentialRepository;
    private final SellerRepository sellerRepository;
    private final ThumbnailTemplateRepository thumbnailTemplateRepository;
    private final DetailTemplateRepository detailTemplateRepository;

    @Override
    @Transactional
    public MarketplaceAccountResponse create(MarketplaceAccountRequest req) {
        Platform platform = requireCoupang(req.getPlatform());

        // secretKey is optional on update (blank keeps existing) but required on create
        if (!StringUtils.hasText(req.getSecretKey())) {
            throw new IllegalArgumentException("secretKey is required");
        }

        Seller seller = sellerRepository.findById(req.getSellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", req.getSellerId()));

        MarketplaceAccount account = MarketplaceAccount.builder()
                .seller(seller)
                .platform(platform)
                .accountAlias(req.getAccountAlias())
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                // Channel template override (21): validate id when provided (404), else null = tenant default.
                .thumbnailTemplate(resolveThumbnailTemplate(req.getThumbnailTemplateId()))
                .detailTemplate(resolveDetailTemplate(req.getDetailTemplateId()))
                .build();

        MarketplaceAccount saved = repository.save(account);

        // ⚠️ tenantId 는 넣지 않는다 — Hibernate 가 INSERT 시 스탬프한다(직접 세팅하면 assigned != current).
        CoupangAccountCredential credential = credentialRepository.save(CoupangAccountCredential.builder()
                .marketplaceAccount(saved)
                .vendorId(req.getVendorId())
                .vendorUserId(req.getVendorUserId())
                .accessKey(req.getAccessKey())
                .secretKey(req.getSecretKey())
                .build());

        // 🔴 saved.getCoupangCredential() 은 읽기전용 역방향(mappedBy)이라 이 시점에도 null 이다 —
        //    방금 만든 인스턴스를 그대로 넘긴다(refresh 우회 금지).
        return mapToResponse(saved, credential);
    }

    @Override
    public MarketplaceAccountResponse get(Long id) {
        MarketplaceAccount account = findOrThrow(id);
        return mapToResponse(account, account.getCoupangCredential());
    }

    @Override
    public List<MarketplaceAccountResponse> list(Long sellerId) {
        List<MarketplaceAccount> accounts = (sellerId != null)
                ? repository.findBySeller_Id(sellerId)
                : repository.findAll();
        return accounts.stream()
                // EAGER 라 자격증명은 이미 로딩돼 있다(FetchType.EAGER, 2609_26).
                .map(a -> mapToResponse(a, a.getCoupangCredential()))
                .toList();
    }

    @Override
    @Transactional
    public MarketplaceAccountResponse update(Long id, MarketplaceAccountRequest req) {
        Platform platform = requireCoupang(req.getPlatform());
        MarketplaceAccount existing = findOrThrow(id);

        Seller seller = sellerRepository.findById(req.getSellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", req.getSellerId()));

        // secretKey: 요청에 값이 있으면 교체, 빈/누락이면 기존값 유지(재암호화 회피)
        CoupangAccountCredential existingCredential = existing.getCoupangCredential();
        String secretKey = StringUtils.hasText(req.getSecretKey())
                ? req.getSecretKey()
                : (existingCredential != null ? existingCredential.getSecretKey() : null);
        if (!StringUtils.hasText(secretKey)) {
            throw new IllegalArgumentException("secretKey is required");
        }

        // Template override: null keeps existing (secretKey convention), a value re-validates (404) and replaces.
        ThumbnailTemplate thumbnailTemplate = req.getThumbnailTemplateId() != null
                ? resolveThumbnailTemplate(req.getThumbnailTemplateId())
                : existing.getThumbnailTemplate();
        DetailTemplate detailTemplate = req.getDetailTemplateId() != null
                ? resolveDetailTemplate(req.getDetailTemplateId())
                : existing.getDetailTemplate();

        MarketplaceAccount updated = existing.toBuilder()
                .seller(seller)
                .platform(platform)
                .accountAlias(req.getAccountAlias())
                .isActive(req.getIsActive() != null ? req.getIsActive() : existing.getIsActive())
                .thumbnailTemplate(thumbnailTemplate)
                .detailTemplate(detailTemplate)
                .build();
        MarketplaceAccount saved = repository.save(updated);

        // 🔴 기존 행을 toBuilder() 로 갱신한다(id 유지 → UPDATE). 새 인스턴스면 uq_coupang_cred_account 위반.
        //    자격증명이 없던 계정(방어적 허용)은 여기서 새로 만든다.
        CoupangAccountCredential credential = (existingCredential != null
                ? existingCredential.toBuilder()
                : CoupangAccountCredential.builder().marketplaceAccount(saved))
                // vendorUserId: same semantics as vendorId/accessKey — request value directly replaces
                // (full overwrite, not the thumbnailTemplate null-keep pattern).
                .vendorId(req.getVendorId())
                .vendorUserId(req.getVendorUserId())
                .accessKey(req.getAccessKey())
                .secretKey(secretKey)
                .build();

        return mapToResponse(saved, credentialRepository.save(credential));
    }

    @Override
    @Transactional
    public MarketplaceAccountResponse updateRegistrationNameSuffix(Long id, OptionCheckSuffixRequest req) {
        MarketplaceAccount existing = findOrThrow(id);
        MarketplaceAccount updated = existing.toBuilder()
                .optionCheckSuffixEnabled(req.getEnabled())
                .optionCheckSuffix(normalizeSuffix(req.getSuffix()))
                .build();
        return mapToResponse(repository.save(updated), existing.getCoupangCredential());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        repository.delete(findOrThrow(id));
    }

    /**
     * 플랫폼 파싱 + 쿠팡 한정 (PLAN D22).
     *
     * <p>네이버는 자격증명 테이블이 아직 없어(만료되는 OAuth 토큰이라 쿠팡 테이블로 못 덮는다) 계정을
     * 만들 수 없다 — 조용히 자격증명 없는 계정을 남기지 말고 400 으로 끊는다.
     */
    private static Platform requireCoupang(String rawPlatform) {
        Platform platform = Platform.from(rawPlatform);
        if (platform != Platform.COUPANG) {
            throw new IllegalArgumentException("네이버 계정은 아직 지원하지 않습니다");
        }
        return platform;
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

    /**
     * 응답 매핑 — 자격증명을 <b>인자로 받는다</b>(2609_26).
     *
     * <p>🔴 {@code a.getCoupangCredential()} 을 여기서 읽으면 안 된다: 역방향(mappedBy)이라 방금 create 한
     * 계정에서는 같은 트랜잭션에서도 null 이고, 자기가 만든 계정에 400/NPE 를 던지게 된다.
     * cred 가 null 이면(자격증명 없는 계정) 세 필드는 null 로 내려간다.
     */
    private MarketplaceAccountResponse mapToResponse(MarketplaceAccount a, CoupangAccountCredential cred) {
        // secretKey 제외하고 매핑 (민감 자격증명 노출 금지)
        return MarketplaceAccountResponse.builder()
                .id(a.getId())
                .sellerId(a.getSeller().getId())
                .platform(a.getPlatform().name())
                .accountAlias(a.getAccountAlias())
                .vendorId(cred != null ? cred.getVendorId() : null)
                .vendorUserId(cred != null ? cred.getVendorUserId() : null)
                .accessKey(cred != null ? cred.getAccessKey() : null)
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
