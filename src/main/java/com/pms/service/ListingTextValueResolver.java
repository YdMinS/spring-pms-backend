package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.ElevenstChannelDefaultRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.elevenst.ElevenstXml;
import com.pms.service.listing.CellBomResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Template field values the 11st payload reads at [올리기] time (FEATURE_2610_13 / D69 · D73 · D78 · D79).
 *
 * <ul>
 *   <li>{@link #brand} — {@code brandName}: listing field value → master field value → first BOM product's
 *       brand (the same {@link ListingTextBindings} order the detail page uses, D69).</li>
 *   <li>{@link #notice} — {@link #KEY_AS_NOTICE} / {@link #KEY_RETURN_EXCHANGE_NOTICE}: listing → master → the
 *       notice default of the listing's 11st sales channel (FEATURE_2610_16 / D5). Detail template blocks are
 *       not a source.</li>
 * </ul>
 * Values are read fresh on every call — they are not taken from the generated detail HTML (D79).
 */
@Component
@RequiredArgsConstructor
public class ListingTextValueResolver {

    /** D73: A/S notice field key. */
    public static final String KEY_AS_NOTICE = "asNotice";
    /** D73: return/exchange notice field key. */
    public static final String KEY_RETURN_EXCHANGE_NOTICE = "returnExchangeNotice";
    public static final List<String> NOTICE_KEYS = List.of(KEY_AS_NOTICE, KEY_RETURN_EXCHANGE_NOTICE);
    /** D78: 11st takes at most 4000 EUC-KR bytes per notice text. */
    public static final int NOTICE_MAX_BYTES = 4000;
    public static final String NOTICE_TOO_LONG_MESSAGE = "11번가는 약 2,000자까지입니다";

    private final ProductListingOptionRepository productListingOptionRepository;
    private final CellBomResolver cellBomResolver;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ElevenstChannelDefaultRepository elevenstChannelDefaultRepository;

    /** D69: the brand the 11st payload sends, or null when every source is blank. */
    public String brand(ProductListing cell) {
        String value = bindings(cell).get(ListingTextBindings.KEY_BRAND);
        return StringUtils.hasText(value) ? value : null;
    }

    /**
     * FEATURE_2610_16 / D5: the notice text for {@code key}, or null when the listing, the master and the sales
     * channel default are all blank.
     */
    public String notice(ProductListing cell, String key) {
        String bound = bindings(cell).get(key);
        if (StringUtils.hasText(bound)) {
            return bound;
        }
        String channelDefault = channelDefault(cell, key);
        return StringUtils.hasText(channelDefault) ? channelDefault : null;
    }

    /** D78: true when {@code value} fits the 11st notice limit (null fits). */
    public static boolean noticeFits(String value) {
        return value == null || value.getBytes(ElevenstXml.EUC_KR).length <= NOTICE_MAX_BYTES;
    }

    /** D78: 400 when either notice key of {@code fieldValues} is over the 11st limit. */
    public static void requireNoticesFit(Map<String, String> fieldValues) {
        if (fieldValues == null) {
            return;
        }
        for (String key : NOTICE_KEYS) {
            if (!noticeFits(fieldValues.get(key))) {
                throw new IllegalArgumentException(NOTICE_TOO_LONG_MESSAGE);
            }
        }
    }

    /** FEATURE_2610_16 / D5: the cell's (seller, platform) sales channel — the same lookup as {@link ChannelTemplateResolver}. */
    private String channelDefault(ProductListing cell, String key) {
        return marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .flatMap(account -> elevenstChannelDefaultRepository.findByMarketplaceAccountId(account.getId()))
                .map(defaults -> KEY_AS_NOTICE.equals(key) ? defaults.getAsNotice()
                        : KEY_RETURN_EXCHANGE_NOTICE.equals(key) ? defaults.getReturnExchangeNotice() : null)
                .orElse(null);
    }

    private Map<String, String> bindings(ProductListing cell) {
        return ListingTextBindings.resolve(cell, cell.getMasterProduct(), firstBomProduct(cell));
    }

    /** Same source as the detail generator: the cell's first option's first BOM product, or null. */
    private Product firstBomProduct(ProductListing cell) {
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(cell.getId());
        if (options.isEmpty()) {
            return null;
        }
        CellBomResolver.Line first = cellBomResolver.forOption(options.get(0)).first();
        return first == null ? null : first.product();
    }
}
