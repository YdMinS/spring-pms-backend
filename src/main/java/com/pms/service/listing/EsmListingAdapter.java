package com.pms.service.listing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.BusinessException;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.esm.EsmApiClient;
import com.pms.service.esm.EsmCategories;
import com.pms.service.esm.EsmCredentials;
import com.pms.service.esm.EsmKey;
import com.pms.service.esm.EsmNoticeConverter;
import com.pms.service.esm.EsmPaths;
import com.pms.service.esm.EsmText;
import com.pms.service.listing.shipping.EsmShippingKeys;
import com.pms.service.listing.shipping.EsmShippingResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ESM (Gmarket · Auction) {@link ListingChannel} adapter (FEATURE_2610_26 / D2) — HTTP only, no cell state changes.
 *
 * <p>Register = post 20 {@code POST /item/v1/goods} · update = {@code PUT /item/v1/goods/{goodsNo}} with the same full
 * body plus {@code isSell} (D30) · status = post 21 {@code GET …/sell-status} folded over the two sites (D31) · stop =
 * post 21 {@code PUT …/sell-status} with both {@code isSell} false (D33). The review-note lookup keeps the interface
 * default (no call, D32); price push and market import stay unsupported (D41). Both sites are always targeted (D5).</p>
 *
 * <p>Value sources: name D20 · category D13 · notices D16 · D17 · fixed fields D18 · image D22 · detail D21 · options
 * D23 · prices D24 · D54 · stock D27 · D28 · shipping D36 · site discount D39 · managed code D18 · D29.</p>
 */
@Component
@RequiredArgsConstructor
public class EsmListingAdapter implements ListingChannel {

    static final String RULE_BLOCK_PREFIX = "G마켓 · 옥션 규칙에 맞지 않아 올릴 수 없습니다: ";
    static final String NO_GMARKET_CATEGORY = "ESM 이 이 카테고리에 G마켓 카테고리를 짝지어 두지 않았습니다";
    static final String NO_AUCTION_CATEGORY = "ESM 이 이 카테고리에 옥션 카테고리를 짝지어 두지 않았습니다";

    /** D25 ②: post 119 — at most 50 values in one selection option. */
    private static final int MAX_OPTIONS = 50;
    /** D28: post 20 — stock 1 ~ 99999. */
    private static final int MAX_STOCK = 99999;
    /** D25 ⑤: the cheapest option at or above this price allows 1.5×, below it 2× (post 34). */
    private static final BigDecimal RATIO_THRESHOLD = new BigDecimal("10000");
    /** D23 / 11st D103: the option group name when no active option carries a per-option attribute name. */
    private static final String DEFAULT_OPTION_TITLE = "옵션";

    private final EsmApiClient client;
    private final EsmCategories esmCategories;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MasterChannelConfigService masterChannelConfigService;
    private final RegistrationNameGenerator registrationNameGenerator;
    private final OptionCheckSuffixResolver optionCheckSuffixResolver;
    private final EsmShippingResolver shippingResolver;

    @Override
    public Platform platform() {
        return Platform.ESM;
    }

    /** D25: ① shipping → ② over 50 options → ③ duplicate name → ④ total stock 0 → ⑤ price ratio per site (D54). */
    @Override
    public void validateRegistrable(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        requireShipping(shippingResolver.resolve(cell));
        List<Line> lines = activeLines(cell);
        if (lines.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException(RULE_BLOCK_PREFIX + "켜진 옵션이 50개를 넘습니다");
        }
        Set<String> seen = new HashSet<>();
        for (Line line : lines) {
            if (!seen.add(line.option().getOptionName())) {
                throw new IllegalArgumentException(
                        RULE_BLOCK_PREFIX + "옵션 이름이 겹칩니다: " + line.option().getOptionName());
            }
        }
        if (lines.stream().mapToLong(Line::stock).sum() == 0) {
            throw new IllegalArgumentException(RULE_BLOCK_PREFIX + "켜진 옵션의 재고가 모두 0 입니다");
        }
        requireRatio(lines, Line::gmarketPrice, "G마켓");
        requireRatio(lines, Line::auctionPrice, "옥션");
    }

    @Override
    public boolean isShippingReady(ProductListing cell) {
        return EsmShippingResolver.missingLabels(shippingResolver.resolve(cell)).isEmpty();
    }

    /** D29: the master goods number is the market product id; a missing one is a failure. */
    @Override
    public String register(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        EsmKey key = EsmCredentials.of(acct);
        JsonNode body = client.post(EsmPaths.GOODS, buildPayload(cell, gen, key, false), key);
        String goodsNo = body.path("goodsNo").asText("");
        if (!StringUtils.hasText(goodsNo) || "0".equals(goodsNo)) {
            throw new BusinessException(EsmApiClient.messageOf(body, 200), HttpStatus.BAD_GATEWAY);
        }
        return goodsNo;
    }

    /** D30: the same full body plus {@code isSell} per site. */
    @Override
    public void update(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        EsmKey key = EsmCredentials.of(acct);
        client.put(EsmPaths.GOODS + "/" + cell.getPlatformProductId(), buildPayload(cell, gen, key, true), key);
    }

    /**
     * D31: SELLING when either site sells, SUSPENDED when neither; the status text names both sites. On SELLING every
     * active option is reported under its name with our option id (11st D107).
     */
    @Override
    public FetchResult fetchStatus(ProductListing cell, MarketplaceAccount acct) {
        JsonNode body = client.get(String.format(EsmPaths.SELL_STATUS, cell.getPlatformProductId()),
                EsmCredentials.of(acct));
        JsonNode isSell = body.has("isSell") ? body.get("isSell") : body.get("IsSell");
        if (isSell == null || !isSell.has("gmkt") || !isSell.has("iac")) {
            throw new BusinessException("G마켓 · 옥션 상태를 읽지 못했습니다", HttpStatus.BAD_GATEWAY);
        }
        boolean gmarket = isSell.get("gmkt").asBoolean();
        boolean auction = isSell.get("iac").asBoolean();
        ListingStatus status = gmarket || auction ? ListingStatus.SELLING : ListingStatus.SUSPENDED;
        String statusName = "G마켓 " + (gmarket ? "판매중" : "판매중지") + " · 옥션 " + (auction ? "판매중" : "판매중지");
        List<FetchResult.OptionId> options = new ArrayList<>();
        if (status == ListingStatus.SELLING) {
            for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
                if (Boolean.TRUE.equals(option.getActive())) {
                    options.add(new FetchResult.OptionId(option.getOptionName(), String.valueOf(option.getId()), null));
                }
            }
        }
        // FEATURE_2610_27 / D37: the same answer carries each site's price (itemBasicInfo.Price) — kept for
        // readMarketPrices.
        JsonNode price = child(child(body, "itemBasicInfo"), "Price");
        return new FetchResult(status, options, statusName, new FetchResult.Prices(
                decimalOrNull(child(price, "gmkt")), decimalOrNull(child(price, "iac")), Map.of()));
    }

    /**
     * FEATURE_2610_27 / D16 · D30 · D37: one goods GET (post 20). Option price per site = the site price from
     * {@link #fetchStatus} + {@code addAmntSite.gmkt} / {@code .iac}, paired by {@code manageCode} (= our option id). When
     * no pair is found and the cell has exactly one active option, that option takes the site prices (a single option
     * is sent as {@code type} 0 — {@link #buildPayload}). Keys are matched ignoring case (D37).
     */
    @Override
    public MarketPrices readMarketPrices(ProductListing cell, MarketplaceAccount acct, FetchResult fetched) {
        BigDecimal gmarketBase = fetched.prices() == null ? null : fetched.prices().base();
        BigDecimal auctionBase = fetched.prices() == null ? null : fetched.prices().auctionBase();
        JsonNode goods = client.get(EsmPaths.GOODS + "/" + cell.getPlatformProductId(), EsmCredentials.of(acct));
        JsonNode details = child(child(child(child(goods, "itemAddtionalInfo"), "recommendedOpts"), "independent"),
                "details");
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(cell.getId());
        Set<String> ourIds = options.stream().map(o -> String.valueOf(o.getId())).collect(Collectors.toSet());
        Map<Long, MarketPrices.OptionPrice> byOptionId = new LinkedHashMap<>();
        for (JsonNode detail : details) {
            String id = child(detail, "manageCode").asText("");
            if (!ourIds.contains(id)) {
                continue;
            }
            JsonNode add = child(detail, "addAmntSite");
            byOptionId.put(Long.valueOf(id), new MarketPrices.OptionPrice(
                    sum(gmarketBase, decimalOrNull(child(add, "gmkt"))),
                    sum(auctionBase, decimalOrNull(child(add, "iac")))));
        }
        if (byOptionId.isEmpty()) {
            List<ProductListingOption> active = options.stream()
                    .filter(o -> Boolean.TRUE.equals(o.getActive())).toList();
            if (active.size() == 1) {
                byOptionId.put(active.get(0).getId(), new MarketPrices.OptionPrice(gmarketBase, auctionBase));
            }
        }
        return new MarketPrices(byOptionId);
    }

    /** D33: both sites to 「판매중지」 through post 21 — the delete API (post 29) is never called. */
    @Override
    public void delete(ProductListing cell, MarketplaceAccount acct) {
        Map<String, Object> body = ordered("isSell", sites(false, false));
        client.put(String.format(EsmPaths.SELL_STATUS, cell.getPlatformProductId()), body, EsmCredentials.of(acct));
    }

    /** The post 20 body sent by register and update alike (D30) — update adds {@code isSell}. */
    Map<String, Object> buildPayload(ProductListing cell, GeneratedProductData gen, EsmKey key, boolean update) {
        Map<String, String> shipping = shippingResolver.resolve(cell);
        requireShipping(shipping);
        List<Line> lines = activeLines(cell);
        MasterProduct master = cell.getMasterProduct();

        String esmCode = masterChannelConfigService.resolveChannelCategory(cell).category().getCode();
        EsmCategories.SiteLeaves leaves = esmCategories.matchedLeaves(key, esmCode);
        if (leaves.gmarket() == null) {
            throw new IllegalArgumentException(NO_GMARKET_CATEGORY);
        }
        if (leaves.auction() == null) {
            throw new IllegalArgumentException(NO_AUCTION_CATEGORY);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (update) {
            boolean selling = cell.getStatus() != ListingStatus.SUSPENDED;
            payload.put("isSell", sites(selling, selling));
        }

        Map<String, Object> basic = new LinkedHashMap<>();
        basic.put("goodsName", ordered("kor", productName(cell, lines)));
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("site", List.of(
                ordered("siteType", 1, "catCode", leaves.auction()),
                ordered("siteType", 2, "catCode", leaves.gmarket())));
        category.put("esm", ordered("catCode", esmCode));
        basic.put("category", category);
        basic.put("goodsType", 1);
        payload.put("itemBasicInfo", basic);

        BigDecimal minGmarket = min(lines, Line::gmarketPrice);
        BigDecimal minAuction = min(lines, Line::auctionPrice);
        int stockSent = (int) Math.min(MAX_STOCK, lines.stream().mapToLong(Line::sentQty).sum());

        Map<String, Object> additional = new LinkedHashMap<>();
        additional.put("price", ordered("Gmkt", won(minGmarket), "Iac", won(minAuction)));
        additional.put("stock", ordered("Gmkt", stockSent, "Iac", stockSent));
        additional.put("recommendedOpts", options(lines, minGmarket, minAuction));
        additional.put("sellingPeriod", ordered("Gmkt", -1, "Iac", -1));
        additional.put("managedCode", String.valueOf(cell.getId()));
        additional.put("origin", ordered("goodsType", 1, "type", 0));
        additional.put("shipping", shippingBlock(shipping));
        additional.put("officialNotice", officialNotice(master));
        additional.put("isAdultProduct", false);
        additional.put("isVatFree", false);
        additional.put("images", ordered("basicImgURL", gen == null || gen.getThumbnailUrl() == null ? ""
                : gen.getThumbnailUrl()));
        additional.put("descriptions", ordered("kor", ordered("type", 2, "html", detailHtml(gen))));
        additional.put("goodsStatus", 1);
        payload.put("itemAddtionalInfo", additional);

        payload.put("addtionalInfo", ordered("siteDiscount", sites(true, true)));
        return payload;
    }

    /** D23: one selection group with a value per active option; a single active option goes without options. */
    private static Map<String, Object> options(List<Line> lines, BigDecimal minGmarket, BigDecimal minAuction) {
        Map<String, Object> opts = new LinkedHashMap<>();
        if (lines.size() <= 1) {
            opts.put("type", 0);
            return opts;
        }
        List<Map<String, Object>> details = new ArrayList<>();
        for (Line line : lines) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("recommendedOptValueNo", 0);
            detail.put("recommendedOptValue", ordered("koreanText", line.option().getOptionName()));
            detail.put("isSoldOut", line.stock() == 0);
            detail.put("isDisplay", true);
            detail.put("qty", ordered("gmkt", line.sentQty(), "iac", line.sentQty()));
            detail.put("manageCode", String.valueOf(line.option().getId()));
            detail.put("addAmntSite", ordered(
                    "gmkt", won(line.gmarketPrice().subtract(minGmarket)),
                    "iac", won(line.auctionPrice().subtract(minAuction))));
            details.add(detail);
        }
        Map<String, Object> independent = new LinkedHashMap<>();
        independent.put("recommendedOptNo", 0);
        independent.put("recommendedOptName", ordered("koreanText", optionTitle(lines)));
        independent.put("details", details);
        opts.put("type", 1);
        opts.put("isStockManage", true);
        opts.put("inputType", 1);
        opts.put("independent", independent);
        return opts;
    }

    /** D36: place + bundle-fee policy · courier · return address + one-way fee · the same dispatch policy twice. */
    private static Map<String, Object> shippingBlock(Map<String, String> s) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("placeNo", Long.parseLong(s.get(EsmShippingKeys.PLACE_NO)));
        policy.put("feeType", 1);
        policy.put("bundle", ordered("deliveryTmplId", s.get(EsmShippingKeys.BUNDLE_POLICY_NO)));
        long dispatch = Long.parseLong(s.get(EsmShippingKeys.DISPATCH_POLICY_NO));
        Map<String, Object> shipping = new LinkedHashMap<>();
        shipping.put("type", 1);
        shipping.put("companyNo", Long.parseLong(s.get(EsmShippingKeys.COMPANY_NO)));
        shipping.put("policy", policy);
        shipping.put("returnAndExchange", ordered(
                "addrNo", s.get(EsmShippingKeys.RETURN_ADDR_NO),
                "fee", Long.parseLong(s.get(EsmShippingKeys.RETURN_FEE))));
        shipping.put("dispatchPolicyNo", ordered("gmkt", dispatch, "iac", dispatch));
        return shipping;
    }

    /** D16 · D17: the master's product-level notice values through {@link EsmNoticeConverter}. */
    private static Map<String, Object> officialNotice(MasterProduct master) {
        EsmNoticeConverter.Notification notification = EsmNoticeConverter.convert(
                master == null ? null : master.getCategoryNoticeGroup(),
                master == null ? null : master.getCategoryNotices());
        List<Map<String, Object>> details = new ArrayList<>();
        for (EsmNoticeConverter.Line line : notification.lines()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("officialNoticeItemelementCode", line.code());
            detail.put("value", line.value());
            detail.put("isExtraMark", line.extraMark());
            details.add(detail);
        }
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("officialNoticeNo", Integer.parseInt(notification.groupNo()));
        notice.put("details", details);
        return notice;
    }

    /** D21: the stored detail HTML with every {@code max-width:} turned into {@code width:} — for ESM only. */
    static String detailHtml(GeneratedProductData gen) {
        String html = gen == null || gen.getDetailHtml() == null ? "" : gen.getDetailHtml();
        return html.replace("max-width:", "width:");
    }

    /** D20: listing name, else the registration name; cut at 100 bytes (Korean 2). */
    private String productName(ProductListing cell, List<Line> lines) {
        String name = cell.getName();
        if (!StringUtils.hasText(name) && cell.getMasterProduct() != null) {
            name = registrationNameGenerator.generate(cell.getMasterProduct(),
                    lines.stream().map(Line::option).toList(), optionCheckSuffixResolver.resolve(cell));
        }
        return EsmText.cutBytes(name, EsmText.GOODS_NAME_BYTES);
    }

    /** D23 / 11st D103: the active options' per-option attribute names joined with 「/」, cut at 50 bytes. */
    private static String optionTitle(List<Line> lines) {
        Set<String> keys = new LinkedHashSet<>();
        for (Line line : lines) {
            if (line.master() != null && line.master().getCategoryAttributes() != null) {
                keys.addAll(line.master().getCategoryAttributes().keySet());
            }
        }
        String title = keys.isEmpty() ? DEFAULT_OPTION_TITLE : String.join("/", keys);
        return EsmText.cutBytes(title, EsmText.OPTION_NAME_BYTES);
    }

    /** D25 ① · D36: every input key must resolve to a value. */
    private static void requireShipping(Map<String, String> shipping) {
        List<String> missing = EsmShippingResolver.missingLabels(shipping);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("배송설정 미완료 — 누락 필드: " + String.join(", ", missing));
        }
    }

    /** D25 ⑤ · D54: within one site, the dearest option may not exceed 2× (cheapest below 10,000 won) or 1.5×. */
    private static void requireRatio(List<Line> lines, Function<Line, BigDecimal> price, String site) {
        BigDecimal min = min(lines, price);
        Line highest = lines.get(0);
        for (Line line : lines) {
            if (price.apply(line).compareTo(price.apply(highest)) > 0) {
                highest = line;
            }
        }
        boolean low = min.compareTo(RATIO_THRESHOLD) < 0;
        BigDecimal limit = min.multiply(low ? BigDecimal.valueOf(2) : new BigDecimal("1.5"));
        if (price.apply(highest).compareTo(limit) > 0) {
            throw new IllegalArgumentException(RULE_BLOCK_PREFIX + site + ": 가장 비싼 옵션("
                    + highest.option().getOptionName() + " " + won(price.apply(highest)) + "원)이 가장 싼 옵션 가격의 "
                    + (low ? "2" : "1.5") + "배를 넘습니다");
        }
    }

    /** Active options in stored order, each with its master option, both prices and resolved stock (D27 · D28). */
    private List<Line> activeLines(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        Map<Long, MasterProductOption> byId = master == null ? Map.of()
                : masterProductOptionRepository.findByMasterProductId(master.getId()).stream()
                        .collect(Collectors.toMap(MasterProductOption::getId, Function.identity(), (a, b) -> a));
        List<Line> lines = new ArrayList<>();
        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
            if (!Boolean.TRUE.equals(option.getActive())) {
                continue;
            }
            MasterProductOption linked = option.getMasterProductOption() == null ? null
                    : byId.get(option.getMasterProductOption().getId());
            BigDecimal gmarket = option.getSellingPrice() == null ? BigDecimal.ZERO : option.getSellingPrice();
            BigDecimal auction = option.getAuctionSellingPrice() == null ? BigDecimal.ZERO
                    : option.getAuctionSellingPrice();
            lines.add(new Line(option, linked, gmarket, auction, ListingStockPolicy.resolve(option, linked)));
        }
        return lines;
    }

    /** FEATURE_2610_27 / D37: the field of {@code node} named {@code name} ignoring case — a missing node otherwise. */
    private static JsonNode child(JsonNode node, String name) {
        if (node != null && node.isObject()) {
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String field = it.next();
                if (field.equalsIgnoreCase(name)) {
                    return node.get(field);
                }
            }
        }
        return MissingNode.getInstance();
    }

    /** A number or numeric text written by ESM, or null when it is absent or not a number (FEATURE_2610_27 / D36). */
    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** base + add, or null when either is unknown. */
    private static BigDecimal sum(BigDecimal base, BigDecimal add) {
        return base == null || add == null ? null : base.add(add);
    }

    private static Map<String, Object> sites(boolean gmarket, boolean auction) {
        return ordered("gmkt", gmarket, "iac", auction);
    }

    /** A map that keeps the key order given — the JSON body is the same text on every run. */
    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static BigDecimal min(List<Line> lines, Function<Line, BigDecimal> price) {
        return lines.stream().map(price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private static long won(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * @param stock the resolved stock (D27); {@link #sentQty} = what ESM takes — 1 for a sold-out option (D28: ESM
     *              refuses 0, {@code isSoldOut} carries it) and at most 99999
     */
    private record Line(ProductListingOption option, MasterProductOption master, BigDecimal gmarketPrice,
                        BigDecimal auctionPrice, int stock) {

        int sentQty() {
            return stock == 0 ? 1 : Math.min(stock, MAX_STOCK);
        }
    }
}
