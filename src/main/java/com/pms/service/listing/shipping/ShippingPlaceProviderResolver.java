package com.pms.service.listing.shipping;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the {@link ShippingPlaceProvider} adapter for a platform (FEATURE_2608_06 / 72). Spring injects
 * every {@link ShippingPlaceProvider} bean; {@link #resolve} matches on {@link ShippingPlaceProvider#platform()}.
 *
 * <p>⚠️ <b>Deliberately different from {@code CategoryLookupResolver}</b> (which throws 400 for an unsupported
 * platform): here an absent provider is not fatal — it just means "no platform lookup → manual entry" — so this
 * returns an empty {@link Optional} instead of throwing. Do NOT add a {@code supports()} method; one Optional
 * is the single, uniform signal.</p>
 */
@Component
public class ShippingPlaceProviderResolver {

    private final Map<String, ShippingPlaceProvider> byPlatform;

    public ShippingPlaceProviderResolver(List<ShippingPlaceProvider> providers) {
        this.byPlatform = providers.stream()
                .collect(Collectors.toMap(ShippingPlaceProvider::platform, Function.identity()));
    }

    /**
     * @param platform platform key (e.g. "COUPANG")
     * @return the matching provider, or empty when the platform has no lookup (→ manual entry)
     */
    public Optional<ShippingPlaceProvider> resolve(String platform) {
        return Optional.ofNullable(byPlatform.get(platform));
    }
}
