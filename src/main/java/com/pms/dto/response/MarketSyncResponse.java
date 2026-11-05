package com.pms.dto.response;

/**
 * Result of [「마켓 기준으로 맞추기」] (FEATURE_2611_11 / D9 · D11 · D35).
 *
 * @param name             the listing's 「노출상품명」 after the sync
 * @param registrationName the listing's stored 「등록상품명」 after the sync; null = the automatic name is used
 * @param imageNotice      why the photos were left as they were (e.g. 「11번가 사진을 가져오지 못했습니다」); null when the
 *                         photos were taken or the market returned none
 */
public record MarketSyncResponse(String name, String registrationName, String imageNotice) {
}
