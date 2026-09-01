package com.pms.service.listing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.MasterProductService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.coupang.CoupangApiClient;
import com.pms.service.listing.category.CategoryAttribute;
import com.pms.service.listing.category.CategoryMetaSchema;
import com.pms.service.listing.category.CategoryNotice;
import com.pms.service.listing.category.CoupangCategoryMeta;
import com.pms.service.listing.category.OptionCategoryMeta;
import com.pms.service.listing.shipping.ResolvedShippingConfig;
import com.pms.service.listing.shipping.ShippingConfigResolver;
import com.pms.service.listing.shipping.ShippingReadiness;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    // 96 ④: a value made of digits only (optionally signed / decimal) is the one that still needs its unit.
    private static final Pattern NUMERIC_VALUE = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    // 96 ④: Coupang's documented cap for attributeValueName (warn only — see withUnit).
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 30;

    private final CoupangApiClient client;
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
    private final ShippingConfigResolver shippingConfigResolver;

    @Override
    public String platform() {
        return "COUPANG";
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
        String raw = client.get(SELLER_PRODUCTS + "/" + cell.getPlatformProductId(), "", acct);
        JsonNode data = readJson(raw).path("data");
        ListingStatus status = mapStatus(data.path("statusName").asText(""));

        List<FetchResult.OptionId> options = new ArrayList<>();
        for (JsonNode item : data.path("items")) {
            options.add(new FetchResult.OptionId(
                    item.path("itemName").asText(null),
                    asTextOrNull(item, "vendorItemId"),
                    asTextOrNull(item, "sellerProductItemId")));
        }
        return new FetchResult(status, options);
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
        String code = masterChannelConfigService.resolvePlatformCategoryCode(cell);
        CategoryMetaSchema schema = metaAdapter.getMeta(acct, code);

        Map<String, String> masterAttributes = master != null ? master.getCategoryAttributes() : null;
        Map<String, String> masterNotices = master != null ? master.getCategoryNotices() : null;
        Map<String, MasterProductOption> byName = master == null ? Map.of()
                : masterProductOptionRepository.findByMasterProductId(master.getId()).stream()
                        .collect(Collectors.toMap(MasterProductOption::getName, o -> o, (a, b) -> a));
        // 96 ⑨: the required 고시 of the picked 품목군 (same group rule as the payload, ⑩). A legacy master with
        // no stored group is left alone — we cannot tell which group's required set applies, and demanding
        // every group's would make those masters un-registrable.
        List<CategoryNotice> requiredNotices = master != null && master.getCategoryNoticeGroup() != null
                ? noticesOfSelectedGroup(schema, master).stream().filter(CategoryNotice::required).toList()
                : List.of();

        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
            if (!Boolean.TRUE.equals(option.getActive())) {
                continue;   // only active options are pushed → only they need required values
            }
            MasterProductOption mo = byName.get(option.getOptionName());
            // 47/59: every required category attribute must have a non-blank value on each ACTIVE option
            // (master shared default ++ per-option override). Categories whose schema defines no attribute have
            // nothing to send → skipped here rather than at the top of the method, so the notice check below
            // still runs for a notices-only category (96 ⑨).
            if (!bundle && !schema.attributes().isEmpty()) {
                Map<String, String> values = OptionCategoryMeta.merge(
                        masterAttributes, mo != null ? mo.getCategoryAttributes() : null);
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
                Map<String, String> notices = OptionCategoryMeta.merge(
                        masterNotices, mo != null ? mo.getCategoryNotices() : null);
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
        // Category code = the master's standard category × platform, resolved from CategoryMapping (44). The
        // channel-add cell's own category column is null. The resolver THROWS 400 on a missing mapping (never
        // returns null), so by this point the code is always non-null and reused below for the notice groups.
        String categoryCode = masterChannelConfigService.resolvePlatformCategoryCode(cell);
        payload.put("displayCategoryCode", categoryCode);
        payload.put("vendorId", acct.getVendorId());
        // 73: WING login id — Coupang-required, distinct from vendorId (vendor code). Push must not proceed unset.
        if (acct.getVendorUserId() == null || acct.getVendorUserId().isBlank()) {
            throw new IllegalArgumentException("vendorUserId 미설정 — 계정 설정을 먼저 완료하세요");
        }
        payload.put("vendorUserId", acct.getVendorUserId());

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
        // Master carries the shared default values; each option overrides only the keys it provides (59). Fetch
        // the master options in ONE query (N+1 guard); matching axis = ProductListingOption.optionName ↔
        // MasterProductOption.name. master==null (backfill transition) → master values only / empty.
        MasterProduct master = cell.getMasterProduct();
        List<MasterProductOption> masterOptions = master == null ? List.of()
                : masterProductOptionRepository.findByMasterProductId(master.getId());
        Map<String, MasterProductOption> byName = masterOptions.stream()
                .collect(Collectors.toMap(MasterProductOption::getName, Function.identity(), (a, b) -> a));

        // Listing options, queried ONCE and reused for the active-name set (registration name) and items[] below.
        List<ProductListingOption> listingOptions =
                productListingOptionRepository.findByProductListingId(cell.getId());
        List<String> activeOptionNames = listingOptions.stream()
                .filter(o -> Boolean.TRUE.equals(o.getActive()))
                .map(ProductListingOption::getOptionName)
                .toList();
        // Registration name (67): always auto-generated per channel from this cell's active options (32 rule).
        // master null fallback = cell.getName() (backfill transition window). 69: the "옵션확인" suffix is resolved
        // per cell (channel ?? master ?? seller ?? system) — single cell = one account query allowed.
        payload.put("sellerProductName", limitName(master != null
                ? registrationNameGenerator.generate(master, activeOptionNames, masterOptions,
                        optionCheckSuffixResolver.resolve(cell))
                : cell.getName()));
        // 108/D2: 노출상품명 (the name shown on the Coupang sales page) — optional, ≤100 chars, same cap as
        // sellerProductName. Reverses 35's "internal only": when omitted Coupang falls back to the
        // registration name, which is exactly the observed symptom. Blank → omit the key (never send "").
        String displayName = cell.getName();
        if (displayName != null && !displayName.isBlank()) {
            payload.put("displayProductName", limitName(displayName));
        }

        Map<String, String> masterAttributes = master != null ? master.getCategoryAttributes() : null;
        Map<String, String> masterNotices = master != null ? master.getCategoryNotices() : null;
        // The category meta for this code, fetched ONCE and reused for both the notice groups (61/96 ⑩) and the
        // attribute units (96 ④). ⚠️ Do not call getMeta again inside this method — register already pays two
        // calls in total (validateRegistrable + here, accepted per §60); adding a third is pure waste.
        CategoryMetaSchema schema = metaAdapter.getMeta(acct, categoryCode);
        // Notice detail(noticeCategoryDetailName) → group(noticeCategoryName) for this category (61), narrowed
        // to the group the user actually picked (96 ⑩ — 품목군 share notice keys, so a first-wins map tagged the
        // shared ones with whichever group happened to come first).
        Map<String, String> groupByDetail = noticesOfSelectedGroup(schema, master).stream()
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
                    ListingStockPolicy.resolve(option, byName.get(option.getOptionName())));
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

            MasterProductOption mo = byName.get(option.getOptionName());
            // 63: AB forbids attributes ("혼합 구성 상품 등록할 때, 속성 입력할 수 없습니다") → skip the whole block for AB.
            // SINGLE keeps per-item merged category attributes (47/59). notices are NOT forbidden → unchanged below.
            if (!bundle) {
                Map<String, String> attrs = OptionCategoryMeta.merge(
                        masterAttributes, mo != null ? mo.getCategoryAttributes() : null);
                if (!attrs.isEmpty()) {
                    item.put("attributes", toAttributes(attrs, unitByAttr));
                }
            }
            Map<String, String> notices = OptionCategoryMeta.merge(
                    masterNotices, mo != null ? mo.getCategoryNotices() : null);
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

    /** Representation image (imageOrder 0) = the S3 public thumbnail URL Coupang ingests into its CDN. */
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
    private static List<CategoryNotice> noticesOfSelectedGroup(CategoryMetaSchema schema, MasterProduct master) {
        String selected = master != null ? master.getCategoryNoticeGroup() : null;
        if (selected == null || selected.isBlank()) {
            return schema.notices();
        }
        return schema.notices().stream()
                .filter(n -> selected.equals(n.groupName()))
                .toList();
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
