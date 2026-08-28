package com.pms.service.listing;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link ListingChannel} adapter for a platform (FEATURE_2608_06 / 3c). Spring injects every
 * {@link ListingChannel} bean; {@link #resolve} matches on {@link ListingChannel#platform()}. Currently only
 * COUPANG; the NAVER adapter joins later (3d) with no orchestration change.
 */
@Component
public class ListingChannelResolver {

    private final Map<String, ListingChannel> byPlatform;

    public ListingChannelResolver(List<ListingChannel> channels) {
        this.byPlatform = channels.stream()
                .collect(Collectors.toMap(ListingChannel::platform, Function.identity()));
    }

    /**
     * @param platform platform key (e.g. "COUPANG")
     * @return the matching adapter
     * @throws IllegalArgumentException (→ 400) if no adapter handles the platform
     */
    public ListingChannel resolve(String platform) {
        return resolveOptional(platform)
                .orElseThrow(() -> new IllegalArgumentException("미지원 플랫폼: " + platform));
    }

    /**
     * Non-throwing lookup (FEATURE_2608_06 / 77) — for <b>read</b> paths that merely enrich a response with
     * channel-owned info. {@code getGenerated}/{@code regenerate}/{@code updateFieldValues}/{@code updateTags}
     * all share one mapper; using {@link #resolve} there would 400 every one of them the moment a cell on a
     * not-yet-supported platform (NAVER, until 3d) exists. Absent adapter = empty = "no opinion", not an error.
     *
     * @param platform platform key (e.g. "COUPANG")
     * @return the matching adapter, or empty when none handles the platform
     */
    public Optional<ListingChannel> resolveOptional(String platform) {
        return Optional.ofNullable(byPlatform.get(platform));
    }
}
