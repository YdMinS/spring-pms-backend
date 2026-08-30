package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.service.listing.OptionCheckSuffix;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves the "옵션확인" registration-name suffix config (FEATURE_2608_06 / 69) — the toggle + custom text that
 * decorates the {@code options ≥ 2} registration name ({@code multiOptionName}). Mirrors
 * {@link ChannelTemplateResolver}'s channel-override-?? -fallback seam.
 *
 * <p>Resolution is <b>field-wise and independent</b>: {@code enabled} and {@code text} each take the first
 * non-null value along the chain, so one level may set {@code enabled} while inheriting the {@code text} from a
 * lower level (and vice versa):</p>
 *
 * <pre>
 *   enabled = channel(account) ?? master ?? seller ?? system default (false)
 *   text    = channel(account) ?? master ?? seller ?? fallback ("옵션확인")   (blank suffix = inherit)
 * </pre>
 *
 * <p>System default = {@code enabled=false} → <b>nothing configured anywhere means no suffix at all</b> (user
 * decision 2026-08-27: an empty seller/channel/master appends nothing to the multi-option registration name).
 * There is no ON-by-default "옵션확인". The {@code text} fallback ("옵션확인") is only a defensive value for the
 * degenerate case where some level turns {@code enabled=true} but leaves the text blank — the front pairs a
 * non-blank text with every enable, so it is normally unused. The channel override wins over master when both
 * are set (chain order).</p>
 *
 * <p>⚠️ LazyInit: {@link #resolve(ProductListing)} touches {@code cell.seller}/{@code cell.masterProduct} (LAZY)
 * and the account's fields — callers (adapter/service) must be inside a {@code @Transactional} boundary
 * (open-in-view=false). Matrix reads use the pure {@link #resolve(MarketplaceAccount, MasterProduct, Seller)}
 * overload with already-loaded entities (no per-cell account re-query = no N+1).</p>
 */
@Service
@RequiredArgsConstructor
public class OptionCheckSuffixResolver {

    // System default OFF: nothing configured at any level → no suffix (user decision 2026-08-27).
    static final boolean DEFAULT_ENABLED = false;
    // Defensive text fallback for enabled-but-blank (front always pairs a non-blank text with an enable).
    static final String DEFAULT_TEXT = "옵션확인";

    private final MarketplaceAccountRepository marketplaceAccountRepository;

    /**
     * Pure field-wise resolution (no DB) from already-loaded entities — each argument is independently nullable.
     * Java has no {@code ?.}, so each level is an explicit null check.
     */
    public OptionCheckSuffix resolve(MarketplaceAccount account, MasterProduct master, Seller seller) {
        boolean enabled = firstEnabled(
                account != null ? account.getOptionCheckSuffixEnabled() : null,
                master != null ? master.getOptionCheckSuffixEnabled() : null,
                seller != null ? seller.getOptionCheckSuffixEnabled() : null);
        String text = firstText(
                account != null ? account.getOptionCheckSuffix() : null,
                master != null ? master.getOptionCheckSuffix() : null,
                seller != null ? seller.getOptionCheckSuffix() : null);
        return new OptionCheckSuffix(enabled, text);
    }

    /**
     * Per-channel resolution for a single cell: loads the (seller, platform) account (LAZY {@code cell.seller})
     * then delegates to the pure overload. ⚠️ One account query per call — fine for a single cell, but the
     * matrix must NOT call this per-cell (use the pure overload with loaded rows to avoid N+1).
     */
    public OptionCheckSuffix resolve(ProductListing cell) {
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElse(null);
        return resolve(account, cell.getMasterProduct(), cell.getSeller());
    }

    /**
     * Master-level resolution (no channel/seller context) for the master-level registration name (34 /
     * {@code getMasterProduct}) = {@code master ?? system}. The seller default is deliberately skipped: the
     * master-level name is a preview of the master archetype, not a specific channel/seller cell — mixing in a
     * per-seller default would make "which seller?" ambiguous. Actual channel cells still reflect the seller
     * default via {@link #resolve(ProductListing)}, so nothing is lost.
     */
    public OptionCheckSuffix resolveForMaster(MasterProduct master) {
        return resolve(null, master, null);
    }

    /** First non-null enabled flag along the chain, else the system default. */
    private static boolean firstEnabled(Boolean... levels) {
        for (Boolean level : levels) {
            if (level != null) {
                return level;
            }
        }
        return DEFAULT_ENABLED;
    }

    /** First non-blank suffix text along the chain, else the system default (blank = inherit). */
    private static String firstText(String... levels) {
        for (String level : levels) {
            if (level != null && !level.isBlank()) {
                return level;
            }
        }
        return DEFAULT_TEXT;
    }
}
