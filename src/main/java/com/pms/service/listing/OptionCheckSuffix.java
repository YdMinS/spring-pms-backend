package com.pms.service.listing;

/**
 * A fully-resolved "옵션확인" suffix decision (FEATURE_2608_06 / 69) — concrete values, not the nullable
 * per-level overrides. Produced by {@code OptionCheckSuffixResolver} by walking the resolution chain
 * (channel ?? master ?? seller ?? system default) once per field, then consumed by
 * {@code RegistrationNameGenerator.multiOptionName}.
 *
 * @param enabled whether the ` - {text}` suffix is appended (options ≥ 2 path only)
 * @param text    the suffix text (only used when {@code enabled})
 */
public record OptionCheckSuffix(boolean enabled, String text) {
}
