package com.pms.service.esm;

/**
 * The four values an ESM token is made from (FEATURE_2610_26 / D4 · D6). Built from a stored account by
 * {@link EsmCredentials#of}, or from the values just typed on the sales-channel form (key check before saving, D7).
 *
 * @param masterId        ESM+ master ID — the token header {@code kid}
 * @param secretKey       the HS256 signing secret
 * @param gmarketSellerId the Gmarket seller ID ({@code G:} in {@code ssi})
 * @param auctionSellerId the Auction seller ID ({@code A:} in {@code ssi})
 */
public record EsmKey(String masterId, String secretKey, String gmarketSellerId, String auctionSellerId) {
}
