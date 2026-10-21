package com.pms.dto.response;

/**
 * One pickable ESM shipping value (FEATURE_2610_26 / D37 · D68): {@code code} is stored and sent, {@code name} is
 * what the seller sees — bundle-fee policies, dispatch policies and couriers.
 */
public record EsmChoiceResponse(String code, String name) {
}
