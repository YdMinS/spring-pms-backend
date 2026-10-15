package com.pms.service;

import com.pms.domain.CoupangAccountCredential;
import com.pms.domain.DetailTemplate;
import com.pms.domain.ElevenstAccountCredential;
import com.pms.domain.ElevenstChannelDefault;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.ElevenstNoticesRequest;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CoupangAccountCredentialRepository;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.ElevenstAccountCredentialRepository;
import com.pms.repository.ElevenstChannelDefaultRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import com.pms.service.elevenst.ElevenstCredentials;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link MarketplaceAccountService} implementation. JPA persistence + the conditional key replacement (secretKey,
 * and since FEATURE_2610_13 the 11st apiKey).
 *
 * Transaction boundary: class readOnly=true, @Transactional on the write methods.
 *
 * <p>🔴 The account (core) and its platform credential — Coupang ({@link CoupangAccountCredential}) or 11st
 * ({@link ElevenstAccountCredential}, FEATURE_2610_13 / D2) — are saved separately in <b>one transaction</b>
 * (FEATURE_2609_26 / PLAN D15). The request/response DTOs stay flat (vendorId/accessKey/secretKey + apiKey).
 *
 * <p>⚠️ An update refreshes the existing credential row with {@code toBuilder()} — saving a new instance violates
 * {@code uq_coupang_cred_account} / {@code uq_elevenst_cred_account}.
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
    private final ElevenstAccountCredentialRepository elevenstCredentialRepository;
    private final ElevenstChannelDefaultRepository elevenstChannelDefaultRepository;
    private final SellerRepository sellerRepository;
    private final ThumbnailTemplateRepository thumbnailTemplateRepository;
    private final DetailTemplateRepository detailTemplateRepository;

    @Override
    @Transactional
    public MarketplaceAccountResponse create(MarketplaceAccountRequest req) {
        Platform platform = requireSupported(req.getPlatform());
        if (platform == Platform.COUPANG) {
            requireCoupangIdentifiers(req);
            // secretKey is optional on update (blank keeps existing) but required on create
            if (!StringUtils.hasText(req.getSecretKey())) {
                throw new IllegalArgumentException("secretKey is required");
            }
        } else if (!StringUtils.hasText(req.getApiKey())) {
            throw new IllegalArgumentException("11번가 API 키를 입력하세요.");
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

        if (platform == Platform.ELEVENST) {
            // D88: a freshly entered key starts its 180-day clock now.
            ElevenstAccountCredential elevenst = elevenstCredentialRepository.save(ElevenstAccountCredential.builder()
                    .marketplaceAccount(saved)
                    .apiKey(req.getApiKey().trim())
                    .keyLastUsedAt(LocalDateTime.now())
                    .build());
            return mapToResponse(saved, null, elevenst);
        }

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
        return mapToResponse(saved, credential, null);
    }

    @Override
    public MarketplaceAccountResponse get(Long id) {
        MarketplaceAccount account = findOrThrow(id);
        return mapToResponse(account, account.getCoupangCredential(), account.getElevenstCredential());
    }

    @Override
    public List<MarketplaceAccountResponse> list(Long sellerId) {
        List<MarketplaceAccount> accounts = (sellerId != null)
                ? repository.findBySeller_Id(sellerId)
                : repository.findAll();
        return accounts.stream()
                // EAGER 라 자격증명은 이미 로딩돼 있다(FetchType.EAGER, 2609_26).
                .map(a -> mapToResponse(a, a.getCoupangCredential(), a.getElevenstCredential()))
                .toList();
    }

    @Override
    @Transactional
    public MarketplaceAccountResponse update(Long id, MarketplaceAccountRequest req) {
        Platform platform = requireSupported(req.getPlatform());
        MarketplaceAccount existing = findOrThrow(id);
        // FEATURE_2610_13: the credential lives in a per-platform table (D2) — switching platform would orphan it.
        if (existing.getPlatform() != platform) {
            throw new IllegalArgumentException("판매채널의 플랫폼은 바꿀 수 없습니다.");
        }

        Seller seller = sellerRepository.findById(req.getSellerId())
                .orElseThrow(() -> new ResourceNotFoundException("Seller", req.getSellerId()));

        // secretKey: 요청에 값이 있으면 교체, 빈/누락이면 기존값 유지(재암호화 회피)
        CoupangAccountCredential existingCredential = existing.getCoupangCredential();
        String secretKey = null;
        if (platform == Platform.COUPANG) {
            requireCoupangIdentifiers(req);
            secretKey = StringUtils.hasText(req.getSecretKey())
                    ? req.getSecretKey()
                    : (existingCredential != null ? existingCredential.getSecretKey() : null);
            if (!StringUtils.hasText(secretKey)) {
                throw new IllegalArgumentException("secretKey is required");
            }
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

        if (platform == Platform.ELEVENST) {
            return mapToResponse(saved, null, upsertElevenstKey(saved, existing.getElevenstCredential(), req));
        }

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

        return mapToResponse(saved, credentialRepository.save(credential), null);
    }

    /**
     * 11st key on update (D5 · D88): a non-blank key replaces the stored one and restarts the 180-day clock;
     * a blank key keeps the existing row untouched. No row and no key → 400.
     */
    private ElevenstAccountCredential upsertElevenstKey(MarketplaceAccount saved, ElevenstAccountCredential existing,
                                                       MarketplaceAccountRequest req) {
        if (!StringUtils.hasText(req.getApiKey())) {
            if (existing == null) {
                throw new IllegalArgumentException("11번가 API 키를 입력하세요.");
            }
            return existing;
        }
        ElevenstAccountCredential.ElevenstAccountCredentialBuilder builder = existing != null
                ? existing.toBuilder()
                : ElevenstAccountCredential.builder().marketplaceAccount(saved);
        return elevenstCredentialRepository.save(builder
                .apiKey(req.getApiKey().trim())
                .keyLastUsedAt(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional
    public MarketplaceAccountResponse updateRegistrationNameSuffix(Long id, OptionCheckSuffixRequest req) {
        MarketplaceAccount existing = findOrThrow(id);
        MarketplaceAccount updated = existing.toBuilder()
                .optionCheckSuffixEnabled(req.getEnabled())
                .optionCheckSuffix(normalizeSuffix(req.getSuffix()))
                .build();
        return mapToResponse(repository.save(updated), existing.getCoupangCredential(),
                existing.getElevenstCredential());
    }

    @Override
    @Transactional
    public void updateElevenstNotices(Long id, ElevenstNoticesRequest req) {
        MarketplaceAccount account = findOrThrow(id);
        if (account.getPlatform() != Platform.ELEVENST) {
            throw new IllegalArgumentException("11번가 판매채널이 아닙니다");
        }
        String asNotice = normalizeSuffix(req.getAsNotice());
        String returnExchangeNotice = normalizeSuffix(req.getReturnExchangeNotice());
        if (!ListingTextValueResolver.noticeFits(asNotice)
                || !ListingTextValueResolver.noticeFits(returnExchangeNotice)) {
            throw new IllegalArgumentException(ListingTextValueResolver.NOTICE_TOO_LONG_MESSAGE);
        }
        // toBuilder from the existing row keeps its id (UNIQUE per account); a new one starts fresh.
        ElevenstChannelDefault.ElevenstChannelDefaultBuilder builder = elevenstChannelDefaultRepository
                .findByMarketplaceAccountId(id)
                .map(ElevenstChannelDefault::toBuilder)
                .orElseGet(() -> ElevenstChannelDefault.builder().marketplaceAccount(account));
        elevenstChannelDefaultRepository.save(builder
                .asNotice(asNotice)
                .returnExchangeNotice(returnExchangeNotice)
                .build());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        repository.delete(findOrThrow(id));
    }

    /**
     * Platform parsing + supported set: COUPANG and, since FEATURE_2610_13 (D5), ELEVENST.
     *
     * <p>NAVER still has no credential table (expiring OAuth token), so creating one would leave an account
     * without credentials — refuse it with 400 instead.</p>
     */
    private static Platform requireSupported(String rawPlatform) {
        Platform platform = Platform.from(rawPlatform);
        if (platform != Platform.COUPANG && platform != Platform.ELEVENST) {
            throw new IllegalArgumentException("네이버 계정은 아직 지원하지 않습니다");
        }
        return platform;
    }

    /** COUPANG requires its vendor code and access key (the DTO no longer enforces them — 11st has neither). */
    private static void requireCoupangIdentifiers(MarketplaceAccountRequest req) {
        if (!StringUtils.hasText(req.getVendorId())) {
            throw new IllegalArgumentException("vendorId is required");
        }
        if (!StringUtils.hasText(req.getAccessKey())) {
            throw new IllegalArgumentException("accessKey is required");
        }
    }

    /**
     * blank → null (inherit); else trimmed. Shared normalization for the 69 suffix text and the 11st notice
     * defaults (FEATURE_2610_16 / D16).
     */
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
    private MarketplaceAccountResponse mapToResponse(MarketplaceAccount a, CoupangAccountCredential cred,
                                                     ElevenstAccountCredential elevenst) {
        // FEATURE_2610_16 / D7: only an 11st channel carries notice defaults — no lookup for other platforms.
        ElevenstChannelDefault notices = a.getPlatform() == Platform.ELEVENST
                ? elevenstChannelDefaultRepository.findByMarketplaceAccountId(a.getId()).orElse(null)
                : null;
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
                // D88: 11st key age — the key value itself is never mapped.
                .elevenstKeyLastUsedAt(elevenst != null ? elevenst.getKeyLastUsedAt() : null)
                .elevenstKeyRenewalNeeded(elevenst != null
                        ? ElevenstCredentials.renewalNeeded(elevenst, LocalDateTime.now()) : null)
                .asNotice(notices != null ? notices.getAsNotice() : null)
                .returnExchangeNotice(notices != null ? notices.getReturnExchangeNotice() : null)
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
