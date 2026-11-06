package com.pms.service.lotteon;

import com.pms.domain.LotteonAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;

import java.time.LocalDateTime;

/**
 * The single entry point for reading a LotteON key (FEATURE_2610_24 / D35). Mirrors {@code ElevenstCredentials} and
 * {@code EsmCredentials}: callers never read {@code account.getLotteonCredential()} directly.
 */
public final class LotteonCredentials {

    static final String NOT_LOTTEON_MESSAGE = "롯데ON 판매채널이 아닙니다.";
    static final String KEY_MISSING_MESSAGE = "롯데ON 인증키가 설정되지 않았습니다.";

    /** D6: the renewal warning starts this many days after the key was saved (one year minus 30 days). */
    public static final int KEY_RENEWAL_WARNING_DAYS = 335;

    private LotteonCredentials() {
    }

    /**
     * @throws IllegalArgumentException (→ 400) when the account is not LotteON or has no key
     */
    public static LotteonKey of(MarketplaceAccount account) {
        if (account == null || account.getPlatform() != Platform.LOTTEON) {
            throw new IllegalArgumentException(NOT_LOTTEON_MESSAGE);
        }
        LotteonAccountCredential credential = account.getLotteonCredential();
        if (credential == null || credential.getApiKey() == null || credential.getApiKey().isBlank()) {
            throw new IllegalArgumentException(KEY_MISSING_MESSAGE);
        }
        return new LotteonKey(credential.getApiKey(), credential.getTrGrpCd(), credential.getTrNo());
    }

    /** D6: true from {@link #KEY_RENEWAL_WARNING_DAYS} days after the key was saved. */
    public static boolean renewalNeeded(LotteonAccountCredential credential, LocalDateTime now) {
        return credential != null && credential.getKeySavedAt() != null
                && !now.isBefore(credential.getKeySavedAt().plusDays(KEY_RENEWAL_WARNING_DAYS));
    }
}
