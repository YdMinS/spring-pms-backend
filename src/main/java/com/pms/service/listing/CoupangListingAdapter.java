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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
        // Fetch the current full document (kept for parity / future field merges), then re-submit the whole
        // rebuilt object via PUT. Selling-price-only partial updates are a follow-up path (out of scope).
        client.get(SELLER_PRODUCTS + "/" + cell.getPlatformProductId(), "", acct);
        String payload = writeJson(buildPayload(cell, gen, acct));
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
        // 63: AB (mixed-composition) forbids attributes entirely → a required-attribute check would make it
        // un-registrable (contradiction). Skip the whole check for AB; SINGLE keeps it. Same isBundle call as
        // buildPayload (§attributes skip) → the payload and the validation can't structurally diverge.
        // gen is unused here (kept for the ListingChannel contract symmetry — see interface Javadoc).
        MasterProduct master = cell.getMasterProduct();
        if (masterProductService.isBundle(master == null ? null : master.getId())) {
            return;
        }
        // 47/59: every required category attribute must have a non-blank value on each ACTIVE option (master
        // shared default ++ per-option override). Register targets a single (master × channel) cell → one
        // category → one getMeta call (reusing the Coupang concrete metaAdapter, 61). Empty schema (NAVER) skips.
        String code = masterChannelConfigService.resolvePlatformCategoryCode(cell);
        CategoryMetaSchema schema = metaAdapter.getMeta(acct, code);
        if (schema.attributes().isEmpty()) {
            return;
        }
        Map<String, String> masterAttributes = master != null ? master.getCategoryAttributes() : null;
        Map<String, MasterProductOption> byName = master == null ? Map.of()
                : masterProductOptionRepository.findByMasterProductId(master.getId()).stream()
                        .collect(Collectors.toMap(MasterProductOption::getName, o -> o, (a, b) -> a));

        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
            if (!Boolean.TRUE.equals(option.getActive())) {
                continue;   // only active options are pushed → only they need required values
            }
            MasterProductOption mo = byName.get(option.getOptionName());
            Map<String, String> values = OptionCategoryMeta.merge(
                    masterAttributes, mo != null ? mo.getCategoryAttributes() : null);
            for (CategoryAttribute attribute : schema.attributes()) {
                if (attribute.required()) {
                    String value = values.get(attribute.name());
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException(
                                "필수 카테고리 속성 누락: " + option.getOptionName() + " / " + attribute.name());
                    }
                }
            }
        }
    }

    // --- payload (§4-4, summary — not over-detailed) ---

    private Map<String, Object> buildPayload(ProductListing cell, GeneratedProductData gen,
                                             MarketplaceAccount acct) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // Category code = the master's standard category × platform, resolved from CategoryMapping (44). The
        // channel-add cell's own category column is null. The resolver THROWS 400 on a missing mapping (never
        // returns null), so by this point the code is always non-null and reused below for the notice groups.
        String categoryCode = masterChannelConfigService.resolvePlatformCategoryCode(cell);
        payload.put("displayCategoryCode", categoryCode);
        payload.put("vendorId", acct.getVendorId());
        payload.put("contents", gen != null ? gen.getDetailHtml() : null);

        // Representation image = S3 public thumbnail URL (Coupang ingests it into its CDN).
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("imageOrder", 0);
        image.put("imageType", "REPRESENTATION");
        image.put("vendorPath", gen != null ? gen.getThumbnailUrl() : null);
        payload.put("images", List.of(image));

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
        payload.put("sellerProductName", master != null
                ? registrationNameGenerator.generate(master, activeOptionNames, masterOptions,
                        optionCheckSuffixResolver.resolve(cell))
                : cell.getName());

        Map<String, String> masterAttributes = master != null ? master.getCategoryAttributes() : null;
        Map<String, String> masterNotices = master != null ? master.getCategoryNotices() : null;
        // Notice detail(noticeCategoryDetailName) → group(noticeCategoryName) for this category (61). Coupang
        // requires noticeCategoryName on every notice item; the stored values map is pure detail→value, so the
        // group is derived here from the category meta (one extra getMeta per register — accepted, §60).
        Map<String, String> groupByDetail = metaAdapter.getMeta(acct, categoryCode).notices().stream()
                .filter(n -> n.groupName() != null)
                .collect(Collectors.toMap(CategoryNotice::key, CategoryNotice::groupName, (a, b) -> a));

        // 63: bundleType = product-level SINGLE (single composition) / AB (mixed composition). Determined once
        // (loop-invariant local boolean, no N+1) by the master's component count. AB forbids attributes entirely.
        boolean bundle = masterProductService.isBundle(master == null ? null : master.getId());
        payload.put("bundleType", bundle ? "AB" : "SINGLE");

        // items[] (max 200): one per ACTIVE listing option (42 — per-channel subset; inactive options are
        // excluded from the payload but keep their row). New options carry no vendorItemId yet. attributes/
        // notices are assembled per item = merge(master, option) → Coupang shape (empty maps skipped, harmless).
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductListingOption option : listingOptions) {
            if (!Boolean.TRUE.equals(option.getActive())) {
                continue;   // deactivated on this channel → not pushed
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemName", option.getOptionName());
            item.put("salePrice", option.getSellingPrice());
            item.put("unitCount", 1);   // 63: unit quantity (SINGLE = 1; AB unitCount is a live-account follow-up)

            MasterProductOption mo = byName.get(option.getOptionName());
            // 63: AB forbids attributes ("혼합 구성 상품 등록할 때, 속성 입력할 수 없습니다") → skip the whole block for AB.
            // SINGLE keeps per-item merged category attributes (47/59). notices are NOT forbidden → unchanged below.
            if (!bundle) {
                Map<String, String> attrs = OptionCategoryMeta.merge(
                        masterAttributes, mo != null ? mo.getCategoryAttributes() : null);
                if (!attrs.isEmpty()) {
                    item.put("attributes", toAttributes(attrs));
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

        // searchTags (33): channel tags first, then master tags appended (deduped, capped at 20). Coupang field
        // name = searchTags (String array, max 20) — fixture-based, verified against a live account as follow-up.
        payload.put("searchTags", tagMergeService.resolveTags(cell));

        return payload;
    }

    /** Merged category-attribute map → Coupang vendorItem {@code attributes[]} shape (47/59). */
    private static List<Map<String, Object>> toAttributes(Map<String, String> values) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("attributeTypeName", entry.getKey());
            attribute.put("attributeValueName", entry.getValue());
            attributes.add(attribute);
        }
        return attributes;
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
