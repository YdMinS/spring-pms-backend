package com.pms.service.esm;

import com.pms.domain.EsmAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;

/**
 * The single entry point for reading an ESM key (FEATURE_2610_26 / D42). Mirrors {@code CoupangCredentials} and
 * {@code ElevenstCredentials}: callers never read {@code account.getEsmCredential()} directly.
 */
public final class EsmCredentials {

    static final String NOT_ESM_MESSAGE = "G마켓 · 옥션 판매채널이 아닙니다.";
    static final String KEY_MISSING_MESSAGE = "G마켓 · 옥션 키가 설정되지 않았습니다.";

    private EsmCredentials() {
    }

    /**
     * @throws IllegalArgumentException (→ 400) when the account is not ESM or a key value is missing
     */
    public static EsmKey of(MarketplaceAccount account) {
        if (account == null || account.getPlatform() != Platform.ESM) {
            throw new IllegalArgumentException(NOT_ESM_MESSAGE);
        }
        EsmAccountCredential credential = account.getEsmCredential();
        if (credential == null || isBlank(credential.getMasterId()) || isBlank(credential.getSecretKey())
                || isBlank(credential.getGmarketSellerId()) || isBlank(credential.getAuctionSellerId())) {
            throw new IllegalArgumentException(KEY_MISSING_MESSAGE);
        }
        return new EsmKey(credential.getMasterId(), credential.getSecretKey(),
                credential.getGmarketSellerId(), credential.getAuctionSellerId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
