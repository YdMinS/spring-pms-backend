package com.pms.service.listing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.config.CoupangProperties;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.MasterProductService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.coupang.CoupangCredentials;
import com.pms.service.listing.category.CategoryAttribute;
import com.pms.service.listing.category.CategoryMetaSchema;
import com.pms.service.listing.category.CategoryNotice;
import com.pms.service.listing.category.CoupangCategoryMeta;
import com.pms.service.listing.category.OptionCategoryMeta;
import com.pms.service.listing.shipping.ResolvedShippingConfig;
import com.pms.service.listing.shipping.CoupangShippingConfigResolver;
import com.pms.service.listing.shipping.ShippingReadiness;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Coupang {@link ListingChannel} adapter (FEATURE_2608_06 / 3c) — HTTP only, no cell state changes.
 *
 * <p>⚠️ Payload details (§4-4) are this adapter's internal concern and are validated against the local
 * {@link com.pms.service.coupang.MockCoupangApiClient} fixture — real-account required fields
 * (displayCategoryCode/returnCenterCode etc.) are follow-up (verified once a live account exists). A register
 * failure propagates as an exception (no cell last_error column this step — CUT).</p>
 */
@Component
@RequiredArgsConstructor
public class CoupangListingAdapter implements ListingChannel {

    private static final Logger log = LoggerFactory.getLogger(CoupangListingAdapter.class);

    private static final String SELLER_PRODUCTS =
            "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";

    /**
     * 온보딩(2026-09-19): 이미지 경로가 상대일 때 붙이는 접두어.
     *
     * <p>운영 실측(표본 22개 상품 / URL 95개): {@code thumbnailImages} 는 <b>22건 전부(100%)</b> 상대 경로였고
     * {@code detailImages} 는 75개 중 15개(20%)가 상대였다(상대형 2건은 각각 8장·7장이 전부 상대). 상대 경로 앞에
     * 이 접두어를 붙여 내려받으면 <b>30/30 이 HTTP 200</b> 에 {@code image/jpeg}·{@code image/png}(매직바이트 확인),
     * 절대 URL 은 그대로 15/15 가 200 이었다. 호스트는 {@code image1.coupangcdn.com} 단일이고 쿼리스트링은 없었다.</p>
     */
    private static final String IMAGE_HOST_PREFIX = "https://image1.coupangcdn.com/image/";

    /** {@code http://}·{@code https://} 등 스킴이 이미 붙어 있는지 판별한다(RFC 3986 scheme 문법). */
    private static final Pattern URL_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://");

    // Register defaults (73). saleStartedAt = now; saleEndedAt = far future (sale period not user-input).
    private static final DateTimeFormatter SALE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String SALE_ENDED_AT = "2099-12-31T23:59:59";
    // 108/D1: approval request is the operational default (user decision 2026-09-01) — [마켓 등록] and
    // [수정 요청] both submit for review. ⚠️ Trade-off: once approved, the product/options can no longer be
    // physically deleted on Coupang (stop-selling only).
    private static final boolean DEFAULT_REQUESTED = true;
    // 93: contents[] structure. The docs' only sample is TEXT + an HTML string in `content` (no HTML-type
    // sample exists) → both fixed to TEXT; a live-account 400 here is fixed by changing these two constants.
    private static final String CONTENTS_TYPE = "TEXT";
    private static final String CONTENT_DETAIL_TYPE = "TEXT";
    // 93: certifications is required even when no certification applies → a single NOT_REQUIRED sentinel on
    // every item. Parsing the category's real certification types is follow-up (after a live-account error).
    private static final List<Map<String, Object>> NOT_REQUIRED_CERTIFICATIONS =
            List.of(Map.of("certificationType", "NOT_REQUIRED", "certificationCode", ""));
    // 93: Coupang caps sellerProductName at 100 chars.
    private static final int MAX_SELLER_PRODUCT_NAME_LENGTH = 100;
    // 2609_67: Coupang caps the sellerProductName SEARCH term at 20 chars (list API). 🔴 조용히 자르지 않는다 —
    // 잘린 줄 모르면 엉뚱한 결과를 보게 된다(PLAN/D3) → 400 으로 돌려준다.
    private static final int SEARCH_NAME_MAX_LENGTH = 20;
    // 2609_67: documented max is 100; 50 keeps one page useful without inflating the response.
    private static final int SEARCH_PAGE_SIZE = 50;
    // 96 ④: a value made of digits only (optionally signed / decimal) is the one that still needs its unit.
    private static final Pattern NUMERIC_VALUE = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    // 2609_45/D4: "number + trailing text" — group(1) the number, group(2) the suffix (see stripUnit).
    private static final Pattern NUMBER_WITH_SUFFIX = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)(\\D.*)$");
    // 온보딩(2026-09-19): <img src> of a detail-HTML block — group 2/3/4 = double-quoted / single-quoted /
    // bare value. DOTALL so a tag broken across lines still matches (detail HTML is machine-written).
    private static final Pattern IMG_SRC = Pattern.compile(
            "<img\\b[^>]*?\\bsrc\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // 96 ④: Coupang's documented cap for attributeValueName (warn only — see withUnit).
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 30;

    private final CoupangApiClient client;
    // 2609_19: only the per-item price path lives in config (unverified against a live account); the other
    // paths stay class constants.
    private final CoupangProperties properties;
    private final ObjectMapper objectMapper;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    // Concrete (not the CategoryMetaAdapter interface) so a future NAVER impl doesn't make the bean ambiguous;
    // used only to derive the notice detail→group map for the payload (61). No cyclic dependency: the meta
    // adapter depends on the client + ObjectMapper only.
    private final CoupangCategoryMeta metaAdapter;
    private final MasterChannelConfigService masterChannelConfigService;
    private final TagMergeService tagMergeService;
    private final RegistrationNameGenerator registrationNameGenerator;
    private final OptionCheckSuffixResolver optionCheckSuffixResolver;
    private final MasterProductService masterProductService;
    // 75: resolves the shipping config field-wise (channel ?? master ?? account default) instead of reading
    // the raw account config directly. The adapter consumes the resolved record only.
    private final CoupangShippingConfigResolver shippingConfigResolver;

    @Override
    public Platform platform() {
        return Platform.COUPANG;
    }

    @Override
    public String register(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        String payload = writeJson(buildPayload(cell, gen, acct));
        String raw = client.post(SELLER_PRODUCTS, payload, acct);
        // Response data = sellerProductId (number or string) → return as String.
        JsonNode data = readJson(raw).path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("쿠팡 상품등록 응답에 data(sellerProductId) 없음: " + raw);
        }
        return data.asText();
    }

    @Override
    public FetchResult fetchStatus(ProductListing cell, MarketplaceAccount acct) {
        // 2609_22: same GET, one parser. fetchStatus keeps its old contract and just projects the status +
        // option ids out of the fuller read — so the two paths can never drift on what a Coupang key means.
        ImportedProduct product = fetchProduct(cell.getPlatformProductId(), acct);
        List<FetchResult.OptionId> options = new ArrayList<>();
        for (ImportedProduct.Option option : product.options()) {
            options.add(new FetchResult.OptionId(
                    option.itemName(), option.vendorItemId(), option.sellerProductItemId()));
        }
        return new FetchResult(product.status(), options);
    }

    /**
     * 2609_22/D8: read the product as it currently exists on Coupang (import preview + commit).
     *
     * <p>⚠️ Only {@code statusName}/{@code items[]}/{@code itemName}/{@code vendorItemId}/
     * {@code sellerProductItemId} are confirmed against a live account (fetchStatus already read them). The
     * rest ({@code sellerProductName}·{@code displayCategoryCode}·{@code searchTags}·{@code salePrice}·
     * {@code originalPrice}·{@code maximumBuyCount}) are inferred from the register payload schema — every
     * one of them parses to {@code null} when absent rather than throwing, and the import service decides
     * what is fatal.</p>
     *
     * <p>2609_45/D4: the response also carries the category attributes/notices that were already stored on the
     * market, per item. They are flattened into the same map shape we store, and numeric attribute values are
     * stripped back to "number only" (see {@link #stripUnit}).</p>
     */
    @Override
    public ImportedProduct fetchProduct(String platformProductId, MarketplaceAccount acct) {
        String raw = client.get(SELLER_PRODUCTS + "/" + platformProductId, "", acct);
        JsonNode data = readJson(raw).path("data");

        String categoryCode = asTextOrNull(data, "displayCategoryCode");
        // 2609_45/D4: the 기본 단위 of this category, read ONCE and reused for every item below. Skipped
        // entirely when no value even looks like "number + suffix" — fetchStatus goes through this same method
        // and must not pay a second GET for products whose attributes need no stripping.
        Map<String, String> unitByAttr = unitsForImport(data, categoryCode, acct);

        List<ImportedProduct.Option> options = new ArrayList<>();
        for (JsonNode item : data.path("items")) {
            options.add(new ImportedProduct.Option(
                    asTextOrNull(item, "itemName"),
                    asTextOrNull(item, "vendorItemId"),
                    asTextOrNull(item, "sellerProductItemId"),
                    asDecimalOrNull(item, "salePrice"),
                    asDecimalOrNull(item, "originalPrice"),
                    asIntOrNull(item, "maximumBuyCount"),
                    importedAttributes(item, unitByAttr),
                    importedNotices(item)));
        }
        // 73: searchTags lives at the ITEM level on Coupang → read the first item's set (every item carries
        // the same merged list when we push). No items = no tags, not an error.
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : data.path("items").path(0).path("searchTags")) {
            String value = tag.asText(null);
            if (value != null && !value.isBlank()) {
                tags.add(value);
            }
        }
        return new ImportedProduct(
                asTextOrNull(data, "sellerProductName"),
                // 2609_67: 단건 응답에는 brand 가 있다(문서 확인 2026-09-21). 없으면 null — 판정하지 않는다.
                asTextOrNull(data, "brand"),
                categoryCode,
                mapStatus(data.path("statusName").asText("")),
                tags,
                // 2609_45/D4-2: 품목군 is a PRODUCT-level fact — every item repeats the same group.
                asTextOrNull(data.path("items").path(0).path("notices").path(0), "noticeCategoryName"),
                thumbnailImages(data),
                detailImages(data),
                options);
    }

    /**
     * 2609_67: 상품명으로 이 계정의 마켓 상품을 검색한다(목록 API). <b>읽기 전용</b> — 저장 0회, 쿠팡 GET 1회.
     *
     * <p>🔴 목록 응답에는 <b>사진이 없다</b>(쿠팡 스펙, 문서 확인 2026-09-21) — 사진·옵션·속성은
     * {@link #fetchProduct} 로 한 건씩 읽는다. 상태 매핑은 {@link #mapStatus} 를 그대로 쓴다(단건 조회와 같은
     * 뜻이어야 한다).</p>
     *
     * <p>{@code nextToken} 은 응답 최상위 값이고, 마지막 페이지에서는 <b>빈 문자열</b>로 오므로 {@code null} 로
     * 정규화해 내린다 — 소비자는 "null 이면 마지막 페이지" 하나만 보면 된다.</p>
     */
    @Override
    public ChannelProductPage searchProducts(String name, String nextToken, MarketplaceAccount acct) {
        String term = name == null ? "" : name.trim();
        if (term.length() > SEARCH_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("상품명 검색어는 20자까지입니다");
        }
        // 🔴 vendorId 는 이 목록 API 의 필수 파라미터다(fetchProduct/fetchStatus 는 query 가 "" 라 쓰지 않는다).
        StringBuilder query = new StringBuilder()
                .append("vendorId=").append(CoupangCredentials.of(acct).getVendorId())
                .append("&sellerProductName=").append(URLEncoder.encode(term, StandardCharsets.UTF_8))
                .append("&maxPerPage=").append(SEARCH_PAGE_SIZE);
        if (nextToken != null && !nextToken.isBlank()) {
            query.append("&nextToken=").append(URLEncoder.encode(nextToken, StandardCharsets.UTF_8));
        }
        JsonNode body = readJson(client.get(SELLER_PRODUCTS, query.toString(), acct));

        List<ChannelProductSummary> items = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            items.add(new ChannelProductSummary(
                    asTextOrNull(node, "sellerProductId"),
                    asTextOrNull(node, "sellerProductName"),
                    asTextOrNull(node, "brand"),
                    mapStatus(node.path("statusName").asText("")),
                    asTextOrNull(node, "createdAt")));
        }
        String next = asTextOrNull(body, "nextToken");
        return new ChannelProductPage(items, next == null || next.isBlank() ? null : next);
    }

    /**
     * 온보딩(2026-09-19): 대표/썸네일 이미지 URL. {@code images[]} lives at the ITEM level on the way in, the
     * same place {@code buildPayload} writes it — the product-level {@code data.images[]} is read first only
     * because a response that carries it there should not come back empty.
     *
     * <p>🔴 These are the marketplace's PROCESSED images (text and borders burned in). They are returned as a
     * separate list from {@link #detailImages} on purpose — merging the two would make it impossible for the
     * consumer to tell a processed thumbnail from a near-original product photo.</p>
     *
     * <p>Order is the response's own order (it carries the representation image first). Duplicates are dropped
     * because every item repeats the same image set. The only rewriting is {@link #absoluteImageUrl} — Coupang
     * returns these paths without a host (100% of the sample), so they are not usable addresses as sent.</p>
     */
    private static List<String> thumbnailImages(JsonNode data) {
        Set<String> seen = new LinkedHashSet<>();
        collectImages(data.path("images"), seen);
        for (JsonNode item : data.path("items")) {
            collectImages(item.path("images"), seen);
        }
        return List.copyOf(seen);
    }

    /** {@code images[]} → URL list. {@code cdnPath} is the marketplace copy; {@code vendorPath} is ours. */
    private static void collectImages(JsonNode images, Set<String> into) {
        for (JsonNode image : images) {
            String url = asTextOrNull(image, "cdnPath");
            if (url == null || url.isBlank()) {
                url = asTextOrNull(image, "vendorPath");
            }
            if (url != null && !url.isBlank()) {
                into.add(absoluteImageUrl(url.trim()));
            }
        }
    }

    /**
     * 온보딩(2026-09-19): 이미지 값을 주소로 성립하는 형태로 정규화한다 — 이것 하나만 한다.
     *
     * <p>Coupang returns most image paths WITHOUT a scheme or host ({@code vendor_inventory/0e21/….jpg}), which is
     * not a usable address on its own, so a value with no scheme gets {@link #IMAGE_HOST_PREFIX} prepended (a leading
     * {@code /} is dropped first so the result never contains a double slash).</p>
     *
     * <p>A value that already carries a scheme is returned VERBATIM — {@code http://} is NOT promoted to
     * {@code https://}, because these URLs are fetched server-side and rewriting them is a change nothing needs.
     * A protocol-relative value ({@code //host/…}) is completed with {@code https:} — it did not occur in the sample,
     * so it is handled in one line and nothing more.</p>
     */
    private static String absoluteImageUrl(String value) {
        if (URL_SCHEME.matcher(value).find()) {
            return value;
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        return IMAGE_HOST_PREFIX + (value.startsWith("/") ? value.substring(1) : value);
    }

    /**
     * 온보딩(2026-09-19): 상세 콘텐츠 이미지 URL, 설명 흐름 순서 그대로.
     *
     * <p>{@code items[].contents[].contentDetails[]} carries either an image URL directly ({@code detailType}
     * = {@code IMAGE}) or an HTML string ({@code TEXT} — what we ourselves push, see {@link #detailContents}).
     * Both shapes are handled because the response is not ours to choose: an imported product was written by
     * whoever created it on Coupang.</p>
     *
     * <p>🔴 URLs only — nothing is downloaded, copied into our storage or attached to anything here. Values that
     * arrive without a host go through {@link #absoluteImageUrl} (20% of the sample); order is untouched.</p>
     */
    private static List<String> detailImages(JsonNode data) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : data.path("items")) {
            for (JsonNode contents : item.path("contents")) {
                for (JsonNode detail : contents.path("contentDetails")) {
                    String content = asTextOrNull(detail, "content");
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    String type = asTextOrNull(detail, "detailType");
                    if ("IMAGE".equalsIgnoreCase(type) || isBareUrl(content)) {
                        seen.add(absoluteImageUrl(content.trim()));
                    } else {
                        for (String source : imageSources(content)) {
                            seen.add(absoluteImageUrl(source));
                        }
                    }
                }
            }
        }
        return List.copyOf(seen);
    }

    /** A content value that is the URL itself rather than markup around it. */
    private static boolean isBareUrl(String content) {
        String trimmed = content.trim();
        return !trimmed.contains("<") && (trimmed.startsWith("http://") || trimmed.startsWith("https://")
                || trimmed.startsWith("//"));
    }

    /**
     * Every {@code <img src>} of an HTML detail block, in document order (that order IS the explanation flow).
     * {@code &amp;} is unescaped because an HTML attribute carries it escaped while the URL does not.
     */
    private static List<String> imageSources(String html) {
        List<String> sources = new ArrayList<>();
        var matcher = IMG_SRC.matcher(html);
        while (matcher.find()) {
            String src = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            if (src != null && !src.isBlank()) {
                sources.add(src.trim().replace("&amp;", "&"));
            }
        }
        return sources;
    }

    /**
     * 2609_45/D4: {@code items[].attributes[]} → our stored shape ({@code attributeTypeName → attributeValueName}).
     * Blank values are dropped (most of them are blank on a live response) so they never overwrite a real value.
     */
    private static Map<String, String> importedAttributes(JsonNode item, Map<String, String> unitByAttr) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (JsonNode attribute : item.path("attributes")) {
            String name = asTextOrNull(attribute, "attributeTypeName");
            String value = asTextOrNull(attribute, "attributeValueName");
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            attributes.put(name, stripUnit(name, value, unitByAttr));
        }
        return attributes;
    }

    /** 2609_45/D4: {@code items[].notices[]} → {@code noticeCategoryDetailName → content}. Blank values dropped. */
    private static Map<String, String> importedNotices(JsonNode item) {
        Map<String, String> notices = new LinkedHashMap<>();
        for (JsonNode notice : item.path("notices")) {
            String key = asTextOrNull(notice, "noticeCategoryDetailName");
            String content = asTextOrNull(notice, "content");
            if (key == null || key.isBlank() || content == null || content.isBlank()) {
                continue;
            }
            notices.put(key, content);
        }
        return notices;
    }

    /**
     * 2609_45/D4: attribute name → 기본 단위 for the product's own category, but only when it is actually
     * needed. {@code fetchProduct} is also what {@code fetchStatus} calls, so an unconditional meta call would
     * double the Coupang GETs of every approval refresh.
     */
    private Map<String, String> unitsForImport(JsonNode data, String categoryCode, MarketplaceAccount acct) {
        if (categoryCode == null || categoryCode.isBlank() || !hasUnitSuffixCandidate(data)) {
            return Map.of();
        }
        return metaAdapter.getMeta(acct, categoryCode).attributes().stream()
                .filter(a -> a.basicUnit() != null)
                .collect(Collectors.toMap(CategoryAttribute::name, CategoryAttribute::basicUnit, (a, b) -> a));
    }

    /** True when at least one attribute value looks like "number + suffix" — the only case stripUnit can act on. */
    private static boolean hasUnitSuffixCandidate(JsonNode data) {
        for (JsonNode item : data.path("items")) {
            for (JsonNode attribute : item.path("attributes")) {
                String value = asTextOrNull(attribute, "attributeValueName");
                if (value != null && NUMBER_WITH_SUFFIX.matcher(value.trim()).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 2609_45/D4: turn Coupang's {@code attributeValueName} back into our storage convention (number only).
     *
     * <p>🔴 The trailing text is dropped <b>only when it equals that attribute's {@code basicUnit} exactly</b>,
     * and only when what precedes it is a number:
     * {@code "36.9g"}(basicUnit=g) → {@code "36.9"} · {@code "6개"}(basicUnit=개) → {@code "6"} ·
     * {@code "1.5kg"}(basicUnit=g) → <b>{@code "1.5kg"} verbatim</b> (dropping it would mean 1.5g —
     * {@code usableUnits} is not consulted). Non-numeric values (SELECT-type "비건" etc.) are always verbatim.</p>
     *
     * <p>⚠️ This is NOT about double units: {@link #withUnit} only appends to bare numbers, so {@code "6개개"}
     * cannot happen. It exists because the master screen shows a number box + unit dropdown, and {@code "6개"}
     * in that box cannot be edited by the user.</p>
     */
    private static String stripUnit(String name, String value, Map<String, String> unitByAttr) {
        String trimmed = value.trim();
        var matcher = NUMBER_WITH_SUFFIX.matcher(trimmed);
        if (!matcher.matches()) {
            return value;
        }
        String unit = unitByAttr.get(name);
        return matcher.group(2).equals(unit) ? matcher.group(1) : value;
    }

    @Override
    public void update(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        // 108/D3: re-submit the whole rebuilt object via PUT, carrying the update-only identifiers
        // (top-level sellerProductId + per-item sellerProductItemId/vendorItemId) read from our own DB —
        // without them Coupang would create a new product instead of revising this one. No preceding GET: we
        // rebuild the whole document rather than merging the fetched one, so it was a wasted call (rate limit).
        // Selling-price-only partial updates are a follow-up path (out of scope).
        String payload = writeJson(buildPayload(cell, gen, acct, true));
        client.put(SELLER_PRODUCTS, payload, acct);
    }

    @Override
    public void delete(ProductListing cell, MarketplaceAccount acct) {
        // Approved options cannot be physically deleted → stop selling.
        // TODO: confirm the exact stop-selling endpoint against a live account (docs unclear); using the
        //  documented sales/stop PUT for now.
        client.put(SELLER_PRODUCTS + "/" + cell.getPlatformProductId() + "/sales/stop", "{}", acct);
    }

    @Override
    public void updateOptionPrice(ProductListingOption option, BigDecimal price, MarketplaceAccount acct) {
        // The price sits in the path, so a decimal point there is a 400 from Coupang. The service already
        // normalised the value to whole won (2609_19/D13) — this only drops the scale, and UNNECESSARY makes
        // a would-be rounding here fail loudly instead of silently sending a different price than we store.
        String path = properties.getVendorItemPricePath()
                .replace("{vendorItemId}", option.getPlatformOptionId())
                .replace("{price}", price.setScale(0, RoundingMode.UNNECESSARY).toPlainString());
        String raw = client.put(path, "{}", acct);
        // A 2xx can still carry a failure code in the body (same defence as the register response). Surface
        // Coupang's own message — the service reports it per option without failing the whole request.
        JsonNode body = readJson(raw);
        String code = body.path("code").asText("");
        if (!code.isBlank() && !"SUCCESS".equalsIgnoreCase(code) && !"200".equals(code)) {
            throw new IllegalStateException("쿠팡 가격변경 실패: " + body.path("message").asText(raw));
        }
    }

    @Override
    public void validateRegistrable(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        // gen is unused here (kept for the ListingChannel contract symmetry — see interface Javadoc).
        MasterProduct master = cell.getMasterProduct();
        // 63: AB (mixed-composition) forbids attributes entirely → a required-attribute check would make it
        // un-registrable (contradiction). Same isBundle call as buildPayload (§attributes skip) → the payload
        // and the validation can't structurally diverge.
        // ⚠️ 96 ⑨: AB only skips the ATTRIBUTE half. AB still sends notices, so returning here (as this method
        // used to) meant a missing required 고시 reached Coupang untouched.
        boolean bundle = masterProductService.isBundle(master == null ? null : master.getId());
        // 47/59: register targets a single (master × channel) cell → one category → one getMeta call (reusing
        // the Coupang concrete metaAdapter, 61). Empty schema (NAVER) leaves both loops with nothing to check.
        // 2609_45/D10: ONE resolver call gives both the code and whether it is the channel's own category —
        // never recompute `own` here (the D10-1 comparison and the D11 fallback live in the resolver).
        MasterChannelConfigService.ChannelCategory category =
                masterChannelConfigService.resolveChannelCategory(cell);
        CategoryMetaSchema schema = metaAdapter.getMeta(acct, category.category().getCode());
        boolean usesOwnCategory = category.own();

        // 2609_45/D12-1: when the channel uses its OWN category the master values belong to a DIFFERENT
        // category — they must not seed the merge (see the note on the payload builder). The import stored
        // everything this channel needs on the cell options.
        Map<String, String> masterAttributes =
                master != null && !usesOwnCategory ? master.getCategoryAttributes() : null;
        Map<String, String> masterNotices =
                master != null && !usesOwnCategory ? master.getCategoryNotices() : null;
        // 2609_22/D1: master options keyed by id — the single master↔channel matching axis (never the name).
        Map<Long, MasterProductOption> byMasterOptionId = master == null ? Map.of()
                : masterProductOptionRepository.findByMasterProductId(master.getId()).stream()
                        .collect(Collectors.toMap(MasterProductOption::getId, o -> o, (a, b) -> a));
        // 96 ⑨: the required 고시 of the picked 품목군 (same group rule as the payload, ⑩). A legacy master with
        // no stored group is left alone — we cannot tell which group's required set applies, and demanding
        // every group's would make those masters un-registrable.
        // 🔴 2609_45/D12: the group is now 셀 ?? 마스터. Testing the MASTER's group (as this used to) skipped
        //    the required-notice check entirely for a cell that carries its own group and no master group —
        //    exactly the cells this feature creates.
        String selectedNoticeGroup = selectedNoticeGroup(cell, master);
        List<CategoryNotice> requiredNotices = selectedNoticeGroup != null
                ? noticesOfSelectedGroup(schema, selectedNoticeGroup).stream()
                        .filter(CategoryNotice::required).toList()
                : List.of();

        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
            if (!Boolean.TRUE.equals(option.getActive())) {
                continue;   // only active options are pushed → only they need required values
            }
            MasterProductOption mo = linkedMaster(option, byMasterOptionId);
            // 47/59: every required category attribute must have a non-blank value on each ACTIVE option
            // (master shared default ++ per-option override). Categories whose schema defines no attribute have
            // nothing to send → skipped here rather than at the top of the method, so the notice check below
            // still runs for a notices-only category (96 ⑨).
            if (!bundle && !schema.attributes().isEmpty()) {
                Map<String, String> values = mergedAttributes(masterAttributes, mo, option);
                // 93: Coupang requires at least one attribute per item. This only fires when values COULD have
                // been filled (a schema with attributes).
                if (values.isEmpty()) {
                    throw new IllegalArgumentException(
                            "카테고리 속성 미입력: " + option.getOptionName() + " — 속성을 1개 이상 입력하세요");
                }
                // ⑤: 같은 groupNumber 를 가진 MANDATORY 속성은 **그룹 중 하나만** 채우면 충족이다.
                // 실측(72882) `최소 중량`·`최소 용량` 이 둘 다 MANDATORY + groupNumber "1" 인데, 고체/액체
                // 상품은 둘 중 하나만 기재한다(프론트 60 도 중량/용량을 택1 페어로 렌더한다). 개별로 검사하면
                // 어느 상품이든 반드시 하나가 비어 **등록이 영구 차단**된다(2026-08-30 실측 차단).
                Set<String> satisfiedGroups = new HashSet<>();
                Map<String, List<String>> requiredGroups = new LinkedHashMap<>();
                for (CategoryAttribute attribute : schema.attributes()) {
                    if (!attribute.required()) {
                        continue;
                    }
                    String value = values.get(attribute.name());
                    boolean filled = value != null && !value.isBlank();
                    if (attribute.grouped()) {
                        requiredGroups.computeIfAbsent(attribute.groupNumber(), k -> new ArrayList<>())
                                .add(attribute.name());
                        if (filled) {
                            satisfiedGroups.add(attribute.groupNumber());
                        }
                    } else if (!filled) {
                        throw new IllegalArgumentException(
                                "필수 카테고리 속성 누락: " + option.getOptionName() + " / " + attribute.name());
                    }
                }
                for (Map.Entry<String, List<String>> group : requiredGroups.entrySet()) {
                    if (!satisfiedGroups.contains(group.getKey())) {
                        throw new IllegalArgumentException("필수 카테고리 속성 누락: " + option.getOptionName()
                                + " / " + String.join(" 또는 ", group.getValue()) + " 중 하나");
                    }
                }
            }
            // 96 ⑨: a required 고시 owned by the option level (용량/중량/수량 …) sat behind the option editor's
            // "상세입력" toggle and was checked by neither gate — the master gate excludes option-owned notices
            // and the option gate only looked at attributes. Coupang answered with an opaque
            // "'1 번 옵션 의 고시정보' 다시 확인해 주세요". Checked for SINGLE and AB alike.
            if (!requiredNotices.isEmpty()) {
                Map<String, String> notices = mergedNotices(masterNotices, mo, option);
                for (CategoryNotice notice : requiredNotices) {
                    String value = notices.get(notice.key());
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException(
                                "필수 고시 누락: " + option.getOptionName() + " / " + notice.key());
                    }
                }
            }
        }
    }

    // --- payload (§4-4, summary — not over-detailed) ---

    /** Register payload (no update-only identifiers) — see the 4-arg overload. */
    private Map<String, Object> buildPayload(ProductListing cell, GeneratedProductData gen,
                                             MarketplaceAccount acct) {
        return buildPayload(cell, gen, acct, false);
    }

    /**
     * 108/D3: {@code forUpdate=true} attaches the Coupang product-update identifiers (top-level
     * {@code sellerProductId}, per-item {@code sellerProductItemId}/{@code vendorItemId}). They must never
     * leak into a register payload, hence the flag instead of post-decorating the returned map (which would
     * assume items[] order matches the option order).
     *
     * <p>🔴 The per-item ids only exist after approval ({@code fetchStatus} fills them on SELLING), so a
     * not-yet-approved cell updates with identifier-less items = Coupang's replace-all semantics. The
     * top-level {@code sellerProductId} still prevents a duplicate product.</p>
     */
    private Map<String, Object> buildPayload(ProductListing cell, GeneratedProductData gen,
                                             MarketplaceAccount acct, boolean forUpdate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (forUpdate) {
            payload.put("sellerProductId", cell.getPlatformProductId());
        }
        // Category = this channel's OWN marketplace category when it has one, else the master's standard
        // category × platform from CategoryMapping (44, 2609_45/D9). ONE resolver call gives both the code and
        // whether it is the channel's own — never recompute `own` from the column (D10-1/D11 live in the
        // resolver). The resolver THROWS 400 on a missing mapping (never returns null), so by this point the
        // code is always non-null and reused below for the notice groups.
        MasterChannelConfigService.ChannelCategory category =
                masterChannelConfigService.resolveChannelCategory(cell);
        String categoryCode = category.category().getCode();
        boolean usesOwnCategory = category.own();
        payload.put("displayCategoryCode", categoryCode);
        var cred = CoupangCredentials.of(acct);
        payload.put("vendorId", cred.getVendorId());
        // 73: WING login id — Coupang-required, distinct from vendorId (vendor code). Push must not proceed unset.
        if (cred.getVendorUserId() == null || cred.getVendorUserId().isBlank()) {
            throw new IllegalArgumentException("vendorUserId 미설정 — 계정 설정을 먼저 완료하세요");
        }
        payload.put("vendorUserId", cred.getVendorUserId());

        // 73: sale period is not user-input → default now .. far future (Coupang format yyyy-MM-dd'T'HH:mm:ss).
        payload.put("saleStartedAt", LocalDateTime.now().format(SALE_DATE_FORMAT));
        payload.put("saleEndedAt", SALE_ENDED_AT);

        // 73/75: delivery / return-center / outbound-place block, field-wise resolved (channel ?? master ??
        // account default, 75). Missing config or any required value → 400 before the HTTP push.
        ResolvedShippingConfig shipping = requireShippingConfig(cell);
        putShippingBlock(payload, shipping);
        // 75: extra info message (주문제작/설치배송) — optional, attach only when non-blank.
        if (shipping.extraInfoMessage() != null && !shipping.extraInfoMessage().isBlank()) {
            payload.put("extraInfoMessage", shipping.extraInfoMessage());
        }

        // 73: non-exposed draft — save only, no approval request.
        payload.put("requested", DEFAULT_REQUESTED);

        // Category required-attributes + product-info disclosure (47/59) are per-vendorItem in Coupang's model.
        // Master carries the shared default values; each option overrides only the keys it provides (59, and
        // since 2609_22/D5 the cell option may override on top). Fetch the master options in ONE query (N+1
        // guard); matching axis = ProductListingOption.masterProductOption (2609_22/D1, never the name).
        // master==null (backfill transition) → master values only / empty.
        MasterProduct master = cell.getMasterProduct();
        List<MasterProductOption> masterOptions = master == null ? List.of()
                : masterProductOptionRepository.findByMasterProductId(master.getId());
        Map<Long, MasterProductOption> byMasterOptionId = masterOptions.stream()
                .collect(Collectors.toMap(MasterProductOption::getId, Function.identity(), (a, b) -> a));

        // Listing options, queried ONCE and reused for the active-name set (registration name) and items[] below.
        List<ProductListingOption> listingOptions =
                productListingOptionRepository.findByProductListingId(cell.getId());
        // 2609_22/D7: the generator resolves the single-option case through each option's master FK (falling
        // back to the cell's own BOM for a channel-only option) → it takes the rows, not their names.
        List<ProductListingOption> activeOptions = listingOptions.stream()
                .filter(o -> Boolean.TRUE.equals(o.getActive()))
                .toList();
        // Registration name (67): always auto-generated per channel from this cell's active options (32 rule).
        // master null fallback = cell.getName() (backfill transition window). 69: the "옵션확인" suffix is resolved
        // per cell (channel ?? master ?? seller ?? system) — single cell = one account query allowed.
        payload.put("sellerProductName", limitName(master != null
                ? registrationNameGenerator.generate(master, activeOptions,
                        optionCheckSuffixResolver.resolve(cell))
                : cell.getName()));
        // 108/D2: 노출상품명 (the name shown on the Coupang sales page) — optional, ≤100 chars, same cap as
        // sellerProductName. Reverses 35's "internal only": when omitted Coupang falls back to the
        // registration name, which is exactly the observed symptom. Blank → omit the key (never send "").
        String displayName = cell.getName();
        if (displayName != null && !displayName.isBlank()) {
            payload.put("displayProductName", limitName(displayName));
        }

        /*
         * 2609_45/D12-1: when the channel uses its OWN category the master's values are ANOTHER category's
         * values — drop them from the merge base. toAttributes does not filter by schema, so a master-only
         * attribute (e.g. "즉석밥 크기" from the master's rice category) would otherwise be sent on a product
         * that sits in a different category; Coupang either rejects it or, worse, registers the wrong
         * attribute. Everything this channel needs was stored on its cell options at import time.
         * Same category → the usual three-tier merge (master ++ master option ++ cell option, 2609_22/D5).
         */
        Map<String, String> masterAttributes =
                master != null && !usesOwnCategory ? master.getCategoryAttributes() : null;
        Map<String, String> masterNotices =
                master != null && !usesOwnCategory ? master.getCategoryNotices() : null;
        // The category meta for this code, fetched ONCE and reused for both the notice groups (61/96 ⑩) and the
        // attribute units (96 ④). ⚠️ Do not call getMeta again inside this method — register already pays two
        // calls in total (validateRegistrable + here, accepted per §60); adding a third is pure waste.
        CategoryMetaSchema schema = metaAdapter.getMeta(acct, categoryCode);
        // Notice detail(noticeCategoryDetailName) → group(noticeCategoryName) for this category (61), narrowed
        // to the group the user actually picked (96 ⑩ — 품목군 share notice keys, so a first-wins map tagged the
        // shared ones with whichever group happened to come first).
        Map<String, String> groupByDetail = noticesOfSelectedGroup(schema, selectedNoticeGroup(cell, master)).stream()
                .filter(n -> n.groupName() != null)
                .collect(Collectors.toMap(CategoryNotice::key, CategoryNotice::groupName, (a, b) -> a));
        // 96 ④: attribute name → 기본 단위. Coupang has no unit field — the value itself must carry it
        // ("200ml"). Built once, outside the items loop.
        Map<String, String> unitByAttr = schema.attributes().stream()
                .filter(a -> a.basicUnit() != null)
                .collect(Collectors.toMap(CategoryAttribute::name, CategoryAttribute::basicUnit, (a, b) -> a));

        // 63: bundleType = product-level SINGLE (single composition) / AB (mixed composition). Determined once
        // (loop-invariant local boolean, no N+1) by the master's component count. AB forbids attributes entirely.
        boolean bundle = masterProductService.isBundle(master == null ? null : master.getId());
        payload.put("bundleType", bundle ? "AB" : "SINGLE");

        // items[] (max 200): one per ACTIVE listing option (42 — per-channel subset; inactive options are
        // excluded from the payload but keep their row). New options carry no vendorItemId yet. attributes/
        // notices are assembled per item = merge(master, option) → Coupang shape (empty maps skipped, harmless).
        // 73: images / searchTags / contents live at the ITEM level (Coupang: searchTags is item-only, images/
        // contents are used per item). Every active option shares the same representation image, merged tag set
        // (33) and detail HTML — computed once and reused across items.
        List<Map<String, Object>> itemImages = List.of(representationImage(gen));
        List<String> searchTags = tagMergeService.resolveTags(cell);
        String detailHtml = gen != null ? gen.getDetailHtml() : null;
        // 93: contents is required — an empty one comes back as an opaque Coupang error, so fail here where the
        // cause (auto-generation not run) is explicit. Lives in buildPayload, not validateRegistrable, because
        // update(PUT) builds the payload without going through the register-only validation.
        if (detailHtml == null || detailHtml.isBlank()) {
            throw new IllegalArgumentException("상세 HTML 미생성 — 재생성 후 등록하세요");
        }
        List<Map<String, Object>> itemContents = detailContents(detailHtml);

        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductListingOption option : listingOptions) {
            if (!Boolean.TRUE.equals(option.getActive())) {
                continue;   // deactivated on this channel → not pushed
            }
            Map<String, Object> item = new LinkedHashMap<>();
            // 108/D3: existing (approved) options must carry their ids on update; a new option omits the keys
            // entirely (a null value would be read as "clear it").
            if (forUpdate) {
                if (option.getSellerProductItemId() != null) {
                    item.put("sellerProductItemId", option.getSellerProductItemId());
                }
                if (option.getPlatformOptionId() != null) {
                    item.put("vendorItemId", option.getPlatformOptionId());
                }
            }
            item.put("itemName", option.getOptionName());
            // 73: originalPrice = display strike-through (reverse-calc, 73); null → fall back to salePrice.
            item.put("originalPrice", option.getOriginalPrice() != null
                    ? option.getOriginalPrice() : option.getSellingPrice());
            item.put("salePrice", option.getSellingPrice());
            item.put("unitCount", 1);   // 63: unit quantity (SINGLE = 1; AB unitCount is a live-account follow-up)
            // 102: stock = this channel's override ?? the master option's ?? 9999 (unset on both).
            item.put("maximumBuyCount",
                    ListingStockPolicy.resolve(option, linkedMaster(option, byMasterOptionId)));
            // 73: fixed item defaults (standard tax/adult/import flags).
            item.put("maximumBuyForPerson", 0);
            item.put("maximumBuyForPersonPeriod", 1);
            item.put("outboundShippingTimeDay", 1);
            item.put("adultOnly", "EVERYONE");
            item.put("taxType", "TAX");
            item.put("parallelImported", "NOT_PARALLEL_IMPORTED");
            item.put("overseasPurchased", "NOT_OVERSEAS_PURCHASED");
            item.put("pccNeeded", false);
            item.put("certifications", NOT_REQUIRED_CERTIFICATIONS);
            item.put("images", itemImages);
            item.put("searchTags", searchTags);
            item.put("contents", itemContents);

            MasterProductOption mo = linkedMaster(option, byMasterOptionId);
            // 63: AB forbids attributes ("혼합 구성 상품 등록할 때, 속성 입력할 수 없습니다") → skip the whole block for AB.
            // SINGLE keeps per-item merged category attributes (47/59). notices are NOT forbidden → unchanged below.
            if (!bundle) {
                Map<String, String> attrs = mergedAttributes(masterAttributes, mo, option);
                if (!attrs.isEmpty()) {
                    item.put("attributes", toAttributes(attrs, unitByAttr));
                }
            }
            Map<String, String> notices = mergedNotices(masterNotices, mo, option);
            if (!notices.isEmpty()) {
                List<Map<String, Object>> noticeItems = toNotices(notices, groupByDetail);
                if (!noticeItems.isEmpty()) {
                    item.put("notices", noticeItems);
                }
            }
            items.add(item);
        }
        payload.put("items", items);

        return payload;
    }

    /**
     * The master option a cell option is linked to, or null for a channel-only option (2609_22/D2).
     * ⚠️ Reads the FK's id only — safe on a LAZY proxy, so no extra query per option.
     */
    private static MasterProductOption linkedMaster(ProductListingOption option,
                                                    Map<Long, MasterProductOption> byMasterOptionId) {
        MasterProductOption linked = option.getMasterProductOption();
        return linked == null ? null : byMasterOptionId.get(linked.getId());
    }

    /**
     * 2609_22/D5: 셀옵션 ?? 마스터옵션 ?? 마스터. A channel-only option has no master option, so the cell's own
     * values are its only source.
     *
     * <p>⚠️ Nested calls of the SAME two-argument {@code merge} — never add a three-argument overload: the
     * rule "the later argument wins" must live in exactly one place.</p>
     */
    private static Map<String, String> mergedAttributes(Map<String, String> masterAttributes,
                                                        MasterProductOption masterOption,
                                                        ProductListingOption cellOption) {
        return OptionCategoryMeta.merge(
                OptionCategoryMeta.merge(masterAttributes,
                        masterOption != null ? masterOption.getCategoryAttributes() : null),
                cellOption.getCategoryAttributes());
    }

    /** 2609_22/D5: same three-tier merge for the product-info disclosure values. */
    private static Map<String, String> mergedNotices(Map<String, String> masterNotices,
                                                     MasterProductOption masterOption,
                                                     ProductListingOption cellOption) {
        return OptionCategoryMeta.merge(
                OptionCategoryMeta.merge(masterNotices,
                        masterOption != null ? masterOption.getCategoryNotices() : null),
                cellOption.getCategoryNotices());
    }

    /** Representation image (imageOrder 0) = the S3 public thumbnail URL Coupang ingests into its CDN. */    /** Representation image (imageOrder 0) = the S3 public thumbnail URL Coupang ingests into its CDN. */
    private static Map<String, Object> representationImage(GeneratedProductData gen) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("imageOrder", 0);
        image.put("imageType", "REPRESENTATION");
        image.put("vendorPath", gen != null ? gen.getThumbnailUrl() : null);
        return image;
    }

    /**
     * 93: detail HTML → Coupang {@code contents[]} shape (a single TEXT block carrying the whole HTML string).
     * Loop-invariant (every option shares the same detail page) → built once per payload.
     */
    private static List<Map<String, Object>> detailContents(String html) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("content", html);
        detail.put("detailType", CONTENT_DETAIL_TYPE);

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("contentsType", CONTENTS_TYPE);
        contents.put("contentDetails", List.of(detail));
        return List.of(contents);
    }

    /** 93: Coupang caps sellerProductName at 100 chars — hard cut (no ellipsis; it would only cost a char). */
    private static String limitName(String name) {
        if (name == null || name.length() <= MAX_SELLER_PRODUCT_NAME_LENGTH) {
            return name;
        }
        log.warn("[COUPANG-ADAPTER] sellerProductName {}자 → {}자로 절단",
                name.length(), MAX_SELLER_PRODUCT_NAME_LENGTH);
        return name.substring(0, MAX_SELLER_PRODUCT_NAME_LENGTH);
    }

    /**
     * Resolve the cell's shipping config field-wise (channel ?? master ?? account default, 75) and assert
     * every register-required field is present. Missing config / all overrides absent → the required fields
     * come back null → 400 (push must not proceed). {@code remoteAreaDeliverable} is transmitted as the
     * resolved "Y"/"N" String. {@code freeShipOverAmount} is optional (only relevant for CONDITIONAL_FREE).
     */
    /**
     * 77: read-only mirror of {@link #requireShippingConfig} — same rules, no throw. Touches LAZY
     * master/seller through the resolver, so callers must be inside a transaction (open-in-view=false).
     */
    @Override
    public boolean isShippingReady(ProductListing cell) {
        return ShippingReadiness.check(shippingConfigResolver.resolve(cell)).ready();
    }

    private ResolvedShippingConfig requireShippingConfig(ProductListing cell) {
        ResolvedShippingConfig cfg = shippingConfigResolver.resolve(cell);
        // 77: the same judgement the read path exposes as shippingReady (no drift between guard and flag).
        ShippingReadiness.Readiness readiness = ShippingReadiness.check(cfg);
        if (!readiness.missing().isEmpty()) {
            throw new IllegalArgumentException("배송설정 미완료 — 누락 필드: " + String.join(", ", readiness.missing()));
        }
        // 75: Coupang forbids 묶음배송(UNION_DELIVERY) together with 착불(CHARGE_RECEIVED).
        if (readiness.unionChargeConflict()) {
            throw new IllegalArgumentException("배송설정 오류 — 묶음배송(UNION_DELIVERY)은 착불(CHARGE_RECEIVED)과 함께 설정할 수 없습니다");
        }
        return cfg;
    }

    /** Top-level delivery / return-center / outbound-place block from the resolved shipping config (72/73/75). */
    private static void putShippingBlock(Map<String, Object> payload, ResolvedShippingConfig cfg) {
        payload.put("deliveryMethod", cfg.deliveryMethod());
        payload.put("deliveryCompanyCode", cfg.deliveryCompanyCode());
        payload.put("deliveryChargeType", cfg.deliveryChargeType());
        // 96 ⑧: 무료배송(FREE) leaves both columns null, and Coupang rejects the product for the missing
        // '무료배송을 위한 조건 금액'. The FREE→0 rule lives in ShippingReadiness so the register guard and this
        // payload can never drift (77).
        payload.put("deliveryCharge", ShippingReadiness.effectiveDeliveryCharge(cfg));
        payload.put("freeShipOverAmount", ShippingReadiness.effectiveFreeShipOverAmount(cfg));
        payload.put("deliveryChargeOnReturn", cfg.deliveryChargeOnReturn());
        payload.put("remoteAreaDeliverable", cfg.remoteAreaDeliverable());
        payload.put("unionDeliveryType", cfg.unionDeliveryType());
        payload.put("returnCenterCode", cfg.returnCenterCode());
        payload.put("returnChargeName", cfg.returnChargeName());
        payload.put("companyContactNumber", cfg.returnContactNumber());
        payload.put("returnZipCode", cfg.returnZipCode());
        payload.put("returnAddress", cfg.returnAddress());
        payload.put("returnAddressDetail", cfg.returnAddressDetail());
        payload.put("returnCharge", cfg.returnCharge());
        payload.put("outboundShippingPlaceCode", cfg.outboundShippingPlaceCode());
    }

    /**
     * Merged category-attribute map → Coupang vendorItem {@code attributes[]} shape (47/59), with the category's
     * 기본 단위 appended to bare numbers (96 ④).
     *
     * <p>The API has no unit field — the docs spell out that {@code attributeValueName} is "옵션타입명에 해당하는
     * Value를 단위와 함께 입력 (예시 "200ml")"; WING's number+dropdown UI simply concatenates before sending.
     * A live account rejected our numbers-only payload with "유효하지 않은 구매 옵션 값 혹은 단위가 존재합니다".</p>
     */
    private static List<Map<String, Object>> toAttributes(Map<String, String> values,
                                                          Map<String, String> unitByAttr) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("attributeTypeName", entry.getKey());
            attribute.put("attributeValueName", withUnit(entry.getKey(), entry.getValue(), unitByAttr));
            attributes.add(attribute);
        }
        return attributes;
    }

    /**
     * 96 ④: append the attribute's 기본 단위 when the stored value is a bare number. A value that already
     * carries a unit is sent verbatim — the user may have typed a different one (kg vs g) and overwriting it
     * would change the meaning. ⚠️ The stored value is never mutated; the unit is attached at send time only.
     */
    private static String withUnit(String name, String value, Map<String, String> unitByAttr) {
        if (value == null) {
            return null;
        }
        String unit = unitByAttr.get(name);
        String trimmed = value.trim();
        if (unit == null || !NUMERIC_VALUE.matcher(trimmed).matches()) {
            return value;
        }
        String withUnit = trimmed + unit;
        if (withUnit.length() > MAX_ATTRIBUTE_VALUE_LENGTH) {
            // Sent as-is on purpose: truncating "1000그램" mid-unit would change what the value means, and the
            // 30-char cap has never been hit on a live account. Coupang's own error is the better signal.
            log.warn("[COUPANG-ADAPTER] attributeValueName '{}' {}자 — 쿠팡 상한 {}자 초과",
                    withUnit, withUnit.length(), MAX_ATTRIBUTE_VALUE_LENGTH);
        }
        return withUnit;
    }

    /**
     * 96 ⑩: the notices of the 품목군 the user picked on the master ({@code categoryNoticeGroup}, 91).
     * 품목군 share notice keys (농수축산물 ↔ 가공식품 share three), so the detail→group map must be built from
     * one group only — otherwise the shared keys go out labelled with whichever group came first in the schema.
     * A legacy master with no stored group keeps the old first-wins behaviour (no regression).
     */
    private static List<CategoryNotice> noticesOfSelectedGroup(CategoryMetaSchema schema, String selected) {
        if (selected == null || selected.isBlank()) {
            return schema.notices();
        }
        return schema.notices().stream()
                .filter(n -> selected.equals(n.groupName()))
                .toList();
    }

    /**
     * 2609_45/D12: 셀 그룹 ?? 마스터 그룹. When a channel keeps its own category, the 품목군 is that category's,
     * not the master's. null (neither set) keeps 96 ⑩'s first-wins fallback for legacy masters.
     */
    private static String selectedNoticeGroup(ProductListing cell, MasterProduct master) {
        String cellGroup = cell.getCategoryNoticeGroup();
        if (cellGroup != null && !cellGroup.isBlank()) {
            return cellGroup;
        }
        String masterGroup = master != null ? master.getCategoryNoticeGroup() : null;
        return masterGroup == null || masterGroup.isBlank() ? null : masterGroup;
    }

    /**
     * Merged product-info-disclosure map → Coupang vendorItem {@code notices[]} shape (47/59/61). Each item
     * carries the required {@code noticeCategoryName}, derived from {@code groupByDetail}. A detail with no
     * group mapping (unknown/legacy key) is skipped (warn) to avoid pushing an incomplete notice.
     */
    private static List<Map<String, Object>> toNotices(Map<String, String> values,
                                                       Map<String, String> groupByDetail) {
        List<Map<String, Object>> notices = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String group = groupByDetail.get(entry.getKey());
            if (group == null) {
                log.warn("[COUPANG-ADAPTER] notice detail '{}' has no noticeCategoryName group — skipped",
                        entry.getKey());
                continue;
            }
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("noticeCategoryName", group);
            notice.put("noticeCategoryDetailName", entry.getKey());
            notice.put("content", entry.getValue());
            notices.add(notice);
        }
        return notices;
    }

    /** Coupang statusName → cell {@link ListingStatus}. Unknown → SUBMITTED (conservative). */
    private ListingStatus mapStatus(String statusName) {
        return switch (statusName) {
            case "임시저장", "승인요청", "승인대기중", "심사중" -> ListingStatus.SUBMITTED;
            case "승인완료", "부분승인완료" -> ListingStatus.SELLING;
            case "승인반려" -> ListingStatus.REJECTED;
            default -> ListingStatus.SUBMITTED;
        };
    }

    private static String asTextOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** 2609_22: absent / null / non-numeric → null (the import service decides what a missing price means). */
    private static BigDecimal asDecimalOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(v.asText().trim());
        } catch (NumberFormatException e) {
            log.warn("[COUPANG-ADAPTER] {} 숫자 변환 실패: {}", field, v.asText());
            return null;
        }
    }

    /** 2609_22: absent / null / non-numeric → null (stock then falls back through ListingStockPolicy). */
    private static Integer asIntOrNull(JsonNode node, String field) {
        BigDecimal value = asDecimalOrNull(node, field);
        return value == null ? null : value.intValue();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("쿠팡 페이로드 직렬화 실패", e);
        }
    }

    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("[COUPANG-ADAPTER] 응답 파싱 실패: {}", e.getMessage());
            throw new IllegalStateException("쿠팡 응답 파싱 실패", e);
        }
    }
}
