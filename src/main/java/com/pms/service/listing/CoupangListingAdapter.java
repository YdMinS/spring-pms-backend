package com.pms.service.listing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final MasterChannelConfigService masterChannelConfigService;
    private final TagMergeService tagMergeService;
    private final RegistrationNameGenerator registrationNameGenerator;

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

    // --- payload (§4-4, summary — not over-detailed) ---

    private Map<String, Object> buildPayload(ProductListing cell, GeneratedProductData gen,
                                             MarketplaceAccount acct) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // Category now comes from the master (master × platform); the channel-add cell's own category column is
        // null. Resolved-not-set (400) is already validated before registration.
        payload.put("displayCategoryCode",
                masterChannelConfigService.resolveCategory(cell).getPlatformCategoryId());
        // Registration name = rule-generated from the master's components/options (not the free-text
        // master label). master null fallback = cell.getName() (backfill transition window).
        payload.put("sellerProductName",
                cell.getMasterProduct() != null
                        ? registrationNameGenerator.generate(cell.getMasterProduct())
                        : cell.getName());
        payload.put("vendorId", acct.getVendorId());
        payload.put("contents", gen != null ? gen.getDetailHtml() : null);

        // Representation image = S3 public thumbnail URL (Coupang ingests it into its CDN).
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("imageOrder", 0);
        image.put("imageType", "REPRESENTATION");
        image.put("vendorPath", gen != null ? gen.getThumbnailUrl() : null);
        payload.put("images", List.of(image));

        // items[] (max 200): one per listing option (new options carry no vendorItemId yet).
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemName", option.getOptionName());
            item.put("salePrice", option.getSellingPrice());
            items.add(item);
        }
        payload.put("items", items);

        // searchTags (33): channel tags first, then master tags appended (deduped, capped at 20). Coupang field
        // name = searchTags (String array, max 20) — fixture-based, verified against a live account as follow-up.
        payload.put("searchTags", tagMergeService.resolveTags(cell));
        return payload;
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
