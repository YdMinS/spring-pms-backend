package com.pms.service.listing;

import com.pms.domain.Platform;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link ListingChannel} adapter for a platform (FEATURE_2608_06 / 3c). Spring injects the
 * {@link ListingChannel} beans as a list; {@link #resolve} matches on {@link ListingChannel#platform()}. Adapters
 * today: COUPANG, ELEVENST, ESM and LOTTEON; NAVER has none yet.
 */
@Component
public class ListingChannelResolver {

    private final Map<Platform, ListingChannel> byPlatform;

    public ListingChannelResolver(List<ListingChannel> channels) {
        this.byPlatform = channels.stream()
                .collect(Collectors.toMap(ListingChannel::platform, Function.identity()));
    }

    /**
     * @param platform platform key (e.g. "COUPANG")
     * @return the matching adapter
     * @throws IllegalArgumentException (→ 400) if no adapter handles the platform
     */
    public ListingChannel resolve(Platform platform) {
        return resolveOptional(platform)
                .orElseThrow(() -> new IllegalArgumentException("미지원 플랫폼: " + platform));
    }

    /**
     * Non-throwing lookup (FEATURE_2608_06 / 77) for callers that treat a platform without an adapter (NAVER
     * today) as "no opinion" instead of a 400: the generated-product response (shippingReady), the option price
     * and stock writes (ListingOptionServiceImpl), the stock push after a save (ChannelStockPushServiceImpl,
     * FEATURE_2611_19) and repricing (RepricingServiceImpl). Absent adapter = empty, not an error.
     *
     * @param platform platform key (e.g. "COUPANG")
     * @return the matching adapter, or empty when none handles the platform
     */
    public Optional<ListingChannel> resolveOptional(Platform platform) {
        return Optional.ofNullable(byPlatform.get(platform));
    }
}
