package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Channel (seller × platform) template resolution seam (FEATURE_2608_06 / 21) — shared by thumbnail and detail
 * auto-generation.
 *
 * <p>Instead of forcing one tenant default template per channel, the {@link MarketplaceAccount}'s
 * {@code thumbnailTemplate}/{@code detailTemplate} assignment is resolved first, and when unassigned (no account
 * or FK null) it falls back to the tenant default template ({@code findByIsDefaultTrueAndActiveTrue}). So a
 * template always resolves even before the account keys are set (backward compatible).</p>
 *
 * <p>The resolution order differs between detail and thumbnail (2609_20/D2·D3):
 * <ul>
 *   <li>Detail = <b>cell ({@code cell.detailTemplate}) → account → tenant default</b> — the cell pin wins.</li>
 *   <li>Thumbnail = account → tenant default (no cell tier — 2609_20 covers detail only).</li>
 * </ul>
 *
 * <p>⚠️ LazyInit guard (same shape as the old {@code account.getSeller()} bug): this resolver touches
 * {@code cell.seller} (LAZY), {@code cell.detailTemplate} (LAZY, 2609_20) and
 * {@code acct.thumbnailTemplate/detailTemplate} (LAZY). The callers (thumbnail =
 * {@code ListingAssetServiceImpl.regenerateAssets} @Transactional, {@code ListingAssetServiceImpl.previewCollage}
 * and {@code ThumbnailCollageServiceImpl} inside their class-level @Transactional (FEATURE_2610_21), detail =
 * {@code TemplateDetailContentGenerator.generate} called inside it) guarantee they run inside a transaction
 * boundary. When adding a path outside a boundary, initialize seller/template eagerly with an {@code @EntityGraph}
 * on the finder.</p>
 *
 * <p>Usage:
 * <pre>
 * ThumbnailTemplate t = channelTemplateResolver.resolveThumbnail(cell); // account assignment ?? tenant default
 * DetailTemplate d = channelTemplateResolver.resolveDetail(cell);        // cell ?? account ?? tenant default
 * ThumbnailTemplate def = channelTemplateResolver.defaultThumbnail();    // tenant default (FEATURE_2610_21)
 * </pre>
 *
 * <p>❌ Forbidden: querying {@code findByIsDefaultTrueAndActiveTrue} directly from a generator/service to resolve a
 * template (always through this resolver). Out of scope: the default-template seeders and the template services'
 * default demotion, which read it for their own bookkeeping.</p>
 */
@Service
@RequiredArgsConstructor
public class ChannelTemplateResolver {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ThumbnailTemplateRepository thumbnailTemplateRepository;
    private final DetailTemplateRepository detailTemplateRepository;

    /** Account's thumbnail template if assigned, else the tenant default (throws if neither exists). */
    public ThumbnailTemplate resolveThumbnail(ProductListing cell) {
        MarketplaceAccount acct = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElse(null);
        // Java has no ?. — explicit null checks (cell.seller/platform are NOT NULL on channel cells).
        if (acct != null && acct.getThumbnailTemplate() != null) {
            return acct.getThumbnailTemplate();
        }
        return defaultThumbnail();
    }

    /**
     * Tenant default thumbnail template (throws if none exists) — also the collage preview without a listing
     * (FEATURE_2610_21 D46).
     */
    public ThumbnailTemplate defaultThumbnail() {
        return thumbnailTemplateRepository.findByIsDefaultTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalStateException("기본 썸네일 템플릿 없음"));
    }

    /** Cell-pinned detail template, else the account's, else the tenant default (throws if none exists). */
    public DetailTemplate resolveDetail(ProductListing cell) {
        // 2609_20/D2: 셀 override 가 최우선. null 이면 아래 기존 2단(계정 ?? 테넌트 기본) 그대로.
        if (cell.getDetailTemplate() != null) {
            return cell.getDetailTemplate();
        }
        MarketplaceAccount acct = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElse(null);
        if (acct != null && acct.getDetailTemplate() != null) {
            return acct.getDetailTemplate();
        }
        return detailTemplateRepository.findByIsDefaultTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalStateException("기본 상세 템플릿 없음"));
    }
}
