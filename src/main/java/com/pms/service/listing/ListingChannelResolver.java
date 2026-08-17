package com.pms.service.listing;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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
        ListingChannel channel = byPlatform.get(platform);
        if (channel == null) {
            throw new IllegalArgumentException("미지원 플랫폼: " + platform);
        }
        return channel;
    }
}
