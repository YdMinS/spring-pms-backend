package com.pms.service.elevenst;

import com.pms.domain.ElevenstAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;

import java.time.LocalDateTime;

/**
 * The single entry point for reading an 11st API key (FEATURE_2610_13 / D5). Mirrors
 * {@code CoupangCredentials}: callers never read {@code account.getElevenstCredential()} directly.
 */
public final class ElevenstCredentials {

    /** D88: the account screens warn when the key has not been used for more than this many days. */
    public static final int KEY_RENEWAL_WARNING_DAYS = 150;

    private ElevenstCredentials() {
    }

    /**
     * @throws IllegalArgumentException (→ 400) when the account is not 11st or has no key
     */
    public static ElevenstAccountCredential of(MarketplaceAccount account) {
        if (account == null || account.getPlatform() != Platform.ELEVENST) {
            throw new IllegalArgumentException("11번가 판매채널이 아닙니다.");
        }
        ElevenstAccountCredential credential = account.getElevenstCredential();
        if (credential == null || credential.getApiKey() == null || credential.getApiKey().isBlank()) {
            throw new IllegalArgumentException("11번가 API 키가 설정되지 않았습니다.");
        }
        return credential;
    }

    /** D88: true when the key was last used more than {@link #KEY_RENEWAL_WARNING_DAYS} days before {@code now}. */
    public static boolean renewalNeeded(ElevenstAccountCredential credential, LocalDateTime now) {
        return credential != null && credential.getKeyLastUsedAt() != null
                && credential.getKeyLastUsedAt().isBefore(now.minusDays(KEY_RENEWAL_WARNING_DAYS));
    }
}
