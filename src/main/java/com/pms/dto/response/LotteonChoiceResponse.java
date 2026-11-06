package com.pms.dto.response;

/**
 * One pickable LotteON shipping fee policy (FEATURE_2610_24 / D43): {@code code} = {@code dvCstPolNo} is stored and
 * sent, {@code name} = {@code dvCstPolNm} is what the seller sees.
 */
public record LotteonChoiceResponse(String code, String name) {
}
