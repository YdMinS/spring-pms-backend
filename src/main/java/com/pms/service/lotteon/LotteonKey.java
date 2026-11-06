package com.pms.service.lotteon;

/**
 * One LotteON sales channel's key (FEATURE_2610_24 / D4 · D5): the seller's own 「인증키」 plus the seller group code
 * ({@code trGrpCd}) and seller number ({@code trNo}) LotteON returned for it. The two numbers are null on a server that
 * does not call LotteON (D5 · D8).
 */
public record LotteonKey(String apiKey, String trGrpCd, String trNo) {
}
