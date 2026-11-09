package com.pms.service.listing;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.ListingMarketImage;
import com.pms.domain.ListingStatus;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.BusinessException;
import com.pms.repository.ListingMarketImageRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.listing.shipping.LotteonShippingKeys;
import com.pms.service.listing.shipping.LotteonShippingResolver;
import com.pms.service.lotteon.LotteonApiClient;
import com.pms.service.lotteon.LotteonCredentials;
import com.pms.service.lotteon.LotteonKey;
import com.pms.service.lotteon.LotteonNoticeConverter;
import com.pms.service.lotteon.LotteonPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LotteON {@link ListingChannel} adapter (FEATURE_2610_24 / D2) — HTTP only, no cell state changes.
 *
 * <p>Register = 87 · update = 90 then 111 for switched-off items (D24 · D67) · status and product read = 94 (D25 ·
 * D51) · stop = 92 {@code END} (D27) · name search = 93 (D54) · market prices = the 94 answer already read (D59) ·
 * selling price = 91 · stock = 86 (FEATURE_2611_19 / D3 · D5).
 * The display category is looked up on 205 per register / update and not stored (D11). Bodies are JSON
 * ({@link LotteonApiClient}). Field names are the public guide's, first checked on the production server.</p>
 *
 * <p>Value sources: fixed codes D16 · name D18 · detail D19 · photos D20 · D52 · options D21 · D22 · notices D13 ·
 * D63 · tags D28 · shipping D42 · D65 · status D25 · D26 · D61.</p>
 */
@Component
@RequiredArgsConstructor
public class LotteonListingAdapter implements ListingChannel {

    static final String MALL_CODE = "LTON";
    static final String NO_DISPLAY_CATEGORY = "롯데ON 이 이 카테고리에 전시카테고리를 붙여 두지 않았습니다";
    static final String NOT_APPROVED_YET = "롯데ON 승인이 끝난 뒤에 수정할 수 있습니다";
    static final String SHIPPING_MISSING_PREFIX = "배송설정 미완료 — 누락 필드: ";
    static final String STATUS_READ_FAILED = "롯데ON 상태를 읽지 못했습니다: ";
    static final String PRODUCT_READ_FAILED = "롯데ON 상품을 읽지 못했습니다: ";
    static final String SEARCH_FAILED = "롯데ON 상품을 검색하지 못했습니다: ";

    /** D18: 87 {@code spdNm} takes 150 characters. */
    private static final int MAX_PRODUCT_NAME_LENGTH = 150;
    /** D21: 87 {@code optNm} takes 200 characters. */
    private static final int MAX_OPTION_TITLE_LENGTH = 200;
    /** D21 / 11st D103: the option group name when no active option carries a per-option attribute name. */
    private static final String DEFAULT_OPTION_TITLE = "옵션";
    /** D52: at most 10 photos per item. */
    private static final int MAX_ITEM_IMAGES = 10;
    /** D52: 87 {@code epsrTypDtlCd} when the stored photo has no kind. */
    private static final String DEFAULT_IMAGE_KIND = "IMG_SQRE";
    /** D54: one page of the name search. */
    private static final int SEARCH_ROWS = 100;
    private static final DateTimeFormatter LOTTEON_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** FEATURE_2611_19 / D3: 91 {@code hstEndDttm} — the price holds until changed again. */
    private static final String PRICE_END = "99991231235959";
    private static final Pattern IMG_SRC = Pattern.compile("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    /** D59: the key prefix under which {@link #fetchStatus} keeps the item prices by our option number. */
    private static final String BY_OUR_ID = "eitmNo:";

    /** D25: LotteON's own status wording, kept as {@code statusName}. */
    static final String REJECTED_NAME = "반려";
    private static final String WAITING_NAME = "승인대기";
    private static final Map<String, String> SALE_STATUS_NAMES = Map.of(
            "SALE", "판매중", "SOUT", "품절", "STP", "판매중지", "END", "판매종료");

    private final LotteonApiClient client;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MasterChannelConfigService masterChannelConfigService;
    private final RegistrationNameGenerator registrationNameGenerator;
    private final OptionCheckSuffixResolver optionCheckSuffixResolver;
    private final LotteonShippingResolver shippingResolver;
    private final TagMergeService tagMergeService;
    /** D52: the per-option market photos. */
    private final ListingMarketImageRepository marketImageRepository;

    /**
     * D26: the rejection reasons read by the last {@link #fetchStatus} of a cell, handed to {@link #fetchReviewNote}
     * right after it (one 94 call serves both).
     */
    private final Map<Long, String> rejectionByCellId = new ConcurrentHashMap<>();

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /** D23 · D42: the missing shipping setting is the only check before LotteON is called. */
    @Override
    public void validateRegistrable(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        requireShipping(shippingResolver.resolve(cell));
    }

    @Override
    public boolean isShippingReady(ProductListing cell) {
        return LotteonShippingResolver.missingLabels(shippingResolver.resolve(cell)).isEmpty();
    }

    /** D39: the answer's {@code spdNo} is the market product number. */
    @Override
    public String register(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        LotteonKey key = LotteonCredentials.of(acct);
        Map<String, Object> product = buildProduct(cell, gen, key, true);
        JsonNode body = client.post(LotteonPaths.REGISTER, Map.of("spdLst", List.of(product)), key.apiKey());
        String failure = LotteonApiClient.lineFailure(body);
        if (failure != null) {
            throw new BusinessException(failure, HttpStatus.BAD_GATEWAY);
        }
        String spdNo = body.path("data").path(0).path("spdNo").asText("");
        if (spdNo.isBlank()) {
            throw new BusinessException(LotteonApiClient.messageOf(body, 200), HttpStatus.BAD_GATEWAY);
        }
        return spdNo;
    }

    /** D24 · D67: 90, then 111 {@code SOUT} for the items whose option was switched off. */
    @Override
    public void update(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        if (cell.getStatus() == ListingStatus.SUBMITTED) {
            throw new IllegalArgumentException(NOT_APPROVED_YET);
        }
        LotteonKey key = LotteonCredentials.of(acct);
        Map<String, Object> product = buildProduct(cell, gen, key, false);
        JsonNode body = client.post(LotteonPaths.MODIFY, Map.of("spdLst", List.of(product)), key.apiKey());
        String failure = LotteonApiClient.lineFailure(body);
        if (failure != null) {
            throw new BusinessException(failure, HttpStatus.BAD_GATEWAY);
        }
        List<Map<String, Object>> soldOut = new ArrayList<>();
        for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
            if (!Boolean.TRUE.equals(option.getActive()) && StringUtils.hasText(option.getPlatformOptionId())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("trGrpCd", key.trGrpCd());
                item.put("trNo", key.trNo());
                item.put("spdNo", cell.getPlatformProductId());
                item.put("sitmNo", option.getPlatformOptionId());
                item.put("slStatCd", "SOUT");
                soldOut.add(item);
            }
        }
        if (!soldOut.isEmpty()) {
            JsonNode items = client.post(LotteonPaths.ITEM_STATUS, Map.of("sitmLst", soldOut), key.apiKey());
            String itemFailure = LotteonApiClient.lineFailure(items);
            if (itemFailure != null) {
                throw new BusinessException(itemFailure, HttpStatus.BAD_GATEWAY);
            }
        }
    }

    /**
     * FEATURE_2611_19 / D3 · D14: one 91 line — the item's selling price from now (Korean time) to 9999-12-31. No
     * sub-seller number and no discount type. A refused line is thrown with LotteON's own text.
     */
    @Override
    public void updateOptionPrice(ProductListingOption option, BigDecimal price, MarketplaceAccount acct) {
        LotteonKey key = LotteonCredentials.of(acct);
        Map<String, Object> line = itemLine(key, option.getProductListing().getPlatformProductId(), option);
        line.put("slPrc", price.longValueExact());
        line.put("hstStrtDttm", LocalDateTime.now(SyncWindow.KST).format(LOTTEON_TIME));
        line.put("hstEndDttm", PRICE_END);
        JsonNode body = client.post(LotteonPaths.ITEM_PRICE, Map.of("itmPrcLst", List.of(line)), key.apiKey());
        String failure = LotteonApiClient.lineFailure(body);
        if (failure != null) {
            throw new BusinessException(failure, HttpStatus.BAD_GATEWAY);
        }
    }

    /** FEATURE_2611_19 / D5: LotteON takes a stock push per item. */
    @Override
    public boolean pushesStock() {
        return true;
    }

    /** FEATURE_2611_19 / D5: an option LotteON numbered ({@code sitmNo}) on a product LotteON numbered. */
    @Override
    public List<ProductListingOption> stockPushTargets(ProductListing cell, List<ProductListingOption> changed) {
        if (!StringUtils.hasText(cell.getPlatformProductId())) {
            return List.of();
        }
        return changed.stream().filter(o -> StringUtils.hasText(o.getPlatformOptionId())).toList();
    }

    /** FEATURE_2611_19 / D5 · D14 · D21: one 86 line per option; a refusal is that option's only. */
    @Override
    public Map<Long, String> updateOptionStocks(ProductListing cell, List<StockPush> pushes, MarketplaceAccount acct) {
        LotteonKey key = LotteonCredentials.of(acct);
        Map<Long, String> refused = new LinkedHashMap<>();
        for (StockPush push : pushes) {
            Map<String, Object> line = itemLine(key, cell.getPlatformProductId(), push.option());
            line.put("stkQty", push.stock());
            try {
                JsonNode body = client.post(LotteonPaths.ITEM_STOCK, Map.of("itmStkLst", List.of(line)), key.apiKey());
                String failure = LotteonApiClient.lineFailure(body);
                if (failure != null) {
                    refused.put(push.option().getId(), failure);
                }
            } catch (RuntimeException e) {
                refused.put(push.option().getId(), e.getMessage());
            }
        }
        return refused;
    }

    /** FEATURE_2611_19 / D3 · D5: the seller group, seller number, product number and item number of one item. */
    private static Map<String, Object> itemLine(LotteonKey key, String spdNo, ProductListingOption option) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("trGrpCd", key.trGrpCd());
        line.put("trNo", key.trNo());
        line.put("spdNo", spdNo);
        line.put("sitmNo", option.getPlatformOptionId());
        return line;
    }

    /** D27: 92 with {@code END}. */
    @Override
    public void delete(ProductListing cell, MarketplaceAccount acct) {
        LotteonKey key = LotteonCredentials.of(acct);
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("trGrpCd", key.trGrpCd());
        product.put("trNo", key.trNo());
        product.put("spdNo", cell.getPlatformProductId());
        product.put("slStatCd", "END");
        JsonNode body = client.post(LotteonPaths.PRODUCT_STATUS, Map.of("spdLst", List.of(product)), key.apiKey());
        String failure = LotteonApiClient.lineFailure(body);
        if (failure != null) {
            throw new BusinessException(failure, HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * D25 · D26 · D59 · D61: one 94 call. On SELLING each item is reported under the name of our option whose number
     * is its {@code eitmNo}, else whose market option number is its {@code sitmNo} (else skipped), with
     * {@code sitmNo} as the market option number. The item prices are kept for {@link #readMarketPrices}.
     */
    @Override
    public FetchResult fetchStatus(ProductListing cell, MarketplaceAccount acct) {
        JsonNode data = readDetail(cell.getPlatformProductId(), acct, STATUS_READ_FAILED);
        JsonNode approval = data.path("pdAprvStatInfo");
        ListingStatus status = fold(data.path("slStatCd").asText(""), approval);
        if (status == null) {
            throw new BusinessException(STATUS_READ_FAILED + data.path("slStatCd").asText(""),
                    HttpStatus.BAD_GATEWAY);
        }
        String statusName = statusName(status, data.path("slStatCd").asText(""));
        if (status == ListingStatus.REJECTED) {
            rejectionByCellId.put(cell.getId(), rejectionText(approval));
        } else {
            rejectionByCellId.remove(cell.getId());
        }

        List<ProductListingOption> ours = productListingOptionRepository.findByProductListingId(cell.getId());
        List<FetchResult.OptionId> options = new ArrayList<>();
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (JsonNode item : data.path("itmLst")) {
            String sitmNo = item.path("sitmNo").asText("");
            String eitmNo = item.path("eitmNo").asText("");
            BigDecimal price = decimalOrNull(item.get("slPrc"));
            if (!sitmNo.isBlank()) {
                prices.put(sitmNo, price);
            }
            if (!eitmNo.isBlank()) {
                prices.put(BY_OUR_ID + eitmNo, price);
            }
            if (status != ListingStatus.SELLING || sitmNo.isBlank()) {
                continue;
            }
            ProductListingOption match = ours.stream()
                    .filter(o -> String.valueOf(o.getId()).equals(eitmNo)).findFirst()
                    .orElseGet(() -> ours.stream()
                            .filter(o -> sitmNo.equals(o.getPlatformOptionId())).findFirst().orElse(null));
            if (match != null) {
                options.add(new FetchResult.OptionId(match.getOptionName(), sitmNo, null));
            }
        }
        return new FetchResult(status, options, statusName, new FetchResult.Prices(null, null, prices));
    }

    /** D26: the rejection reasons the status read just got — no second call. */
    @Override
    public ReviewNote fetchReviewNote(ProductListing cell, MarketplaceAccount acct, String statusName) {
        if (!REJECTED_NAME.equals(statusName)) {
            return null;
        }
        String text = rejectionByCellId.remove(cell.getId());
        return text == null || text.isBlank() ? ReviewNote.notFound() : ReviewNote.found(text);
    }

    /**
     * D59: no extra call — the item prices of the 94 answer {@link #fetchStatus} read. Our option's market option
     * number ({@code sitmNo}) → that price; else our option number as {@code eitmNo} → that price; else, when the cell
     * has one active option and LotteON one item, that item's price.
     */
    @Override
    public MarketPrices readMarketPrices(ProductListing cell, MarketplaceAccount acct, FetchResult fetched) {
        Map<String, BigDecimal> prices = fetched.prices() == null ? Map.of() : fetched.prices().byMarketOptionId();
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(cell.getId());
        Map<Long, MarketPrices.OptionPrice> byOptionId = new LinkedHashMap<>();
        for (ProductListingOption option : options) {
            String marketId = option.getPlatformOptionId();
            if (marketId != null && prices.containsKey(marketId)) {
                byOptionId.put(option.getId(), new MarketPrices.OptionPrice(prices.get(marketId), null));
            } else if (prices.containsKey(BY_OUR_ID + option.getId())) {
                byOptionId.put(option.getId(),
                        new MarketPrices.OptionPrice(prices.get(BY_OUR_ID + option.getId()), null));
            }
        }
        if (byOptionId.isEmpty()) {
            List<ProductListingOption> active = options.stream()
                    .filter(o -> Boolean.TRUE.equals(o.getActive())).toList();
            List<BigDecimal> itemPrices = prices.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith(BY_OUR_ID)).map(Map.Entry::getValue).toList();
            if (active.size() == 1 && itemPrices.size() == 1) {
                byOptionId.put(active.get(0).getId(), new MarketPrices.OptionPrice(itemPrices.get(0), null));
            }
        }
        return new MarketPrices(byOptionId);
    }

    /** D51 · D52 · D53 · D58 · D63: one 94 call → the shared product read shape. */
    @Override
    public ImportedProduct fetchProduct(String platformProductId, MarketplaceAccount acct) {
        JsonNode data = readDetail(platformProductId, acct, PRODUCT_READ_FAILED);
        String name = data.path("spdNm").asText(null);
        ListingStatus status = fold(data.path("slStatCd").asText(""), data.path("pdAprvStatInfo"));
        String statusName = status == null ? null : statusName(status, data.path("slStatCd").asText(""));

        List<String> tags = new ArrayList<>();
        for (JsonNode tag : data.path("scKwdLst")) {
            if (!tag.asText("").isBlank()) {
                tags.add(tag.asText().trim());
            }
        }
        List<String> detailParts = new ArrayList<>();
        for (JsonNode epn : data.path("epnLst")) {
            if ("DSCRP".equals(epn.path("pdEpnTypCd").asText()) && epn.hasNonNull("cnts")) {
                detailParts.add(epn.path("cnts").asText());
            }
        }
        String detailHtml = detailParts.isEmpty() ? null : String.join("\n", detailParts);
        List<String> detailImages = new ArrayList<>();
        if (detailHtml != null) {
            Matcher matcher = IMG_SRC.matcher(detailHtml);
            while (matcher.find()) {
                detailImages.add(matcher.group(1));
            }
        }

        Map<String, String> noticeValues = new LinkedHashMap<>();
        for (JsonNode artl : data.path("pdItmsInfo").path("pdItmsArtlLst")) {
            noticeValues.put(artl.path("pdArtlCd").asText(), artl.path("pdArtlCnts").asText(""));
        }
        LotteonNoticeConverter.Imported notices = LotteonNoticeConverter.fromLotteon(
                data.path("pdItmsInfo").path("pdItmsCd").asText(null), noticeValues);

        boolean hasOptions = "Y".equals(data.path("sitmYn").asText());
        boolean stockManaged = !"N".equals(data.path("stkMgtYn").asText());
        List<ImportedProduct.Option> options = new ArrayList<>();
        Set<String> thumbnails = new LinkedHashSet<>();
        List<ImportedProduct.MarketImage> images = new ArrayList<>();
        int index = 0;
        for (JsonNode item : data.path("itmLst")) {
            String itemName = itemName(item, hasOptions, name);
            String sitmNo = item.path("sitmNo").asText(null);
            BigDecimal stock = decimalOrNull(item.get("stkQty"));
            options.add(new ImportedProduct.Option(itemName, sitmNo, null, decimalOrNull(item.get("slPrc")), null,
                    !stockManaged || stock == null ? null : stock.intValue(), Map.of(), notices.notices()));
            for (JsonNode image : item.path("itmImgLst")) {
                String url = image.path("origImgFileNm").asText("");
                if (url.isBlank()) {
                    continue;
                }
                thumbnails.add(url);
                images.add(new ImportedProduct.MarketImage(index, sitmNo, itemName,
                        "Y".equals(image.path("rprtImgYn").asText()), image.path("epsrTypDtlCd").asText(null), url));
            }
            index++;
        }
        return new ImportedProduct(null, null, data.path("scatNo").asText(null), status, tags,
                notices.coupangGroup(), List.copyOf(thumbnails), detailImages, options, statusName, name, null,
                detailHtml, images, null);
    }

    /** D54: one 93 call — up to 100 products, no next page. Read only. */
    @Override
    public ChannelProductPage searchProducts(String name, String nextToken, MarketplaceAccount acct) {
        LotteonKey key = LotteonCredentials.of(acct);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("trGrpCd", key.trGrpCd());
        request.put("trNo", key.trNo());
        request.put("regStrtDttm", "20000101000000");
        request.put("regEndDttm", "99991231235959");
        request.put("spdNm", name);
        request.put("pageNo", 1);
        request.put("rowsPerPage", SEARCH_ROWS);
        JsonNode body;
        try {
            body = client.post(LotteonPaths.PRODUCT_LIST, request, key.apiKey());
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.BAD_GATEWAY) {
                throw new BusinessException(SEARCH_FAILED + e.getMessage(), HttpStatus.BAD_GATEWAY);
            }
            throw e;
        }
        List<ChannelProductSummary> items = new ArrayList<>();
        for (JsonNode product : body.path("data")) {
            items.add(new ChannelProductSummary(product.path("spdNo").asText(null), product.path("spdNm").asText(null),
                    null, fold(product.path("slStatCd").asText(""), product), product.path("regDttm").asText(null)));
        }
        return new ChannelProductPage(items, null);
    }

    /** The 87 / 90 product body (D16 ~ D22 · D28 · D42 · D52 · D63 · D67) — one place for both. */
    Map<String, Object> buildProduct(ProductListing cell, GeneratedProductData gen, LotteonKey key,
                                     boolean register) {
        Map<String, String> shipping = shippingResolver.resolve(cell);
        requireShipping(shipping);
        List<Line> lines = activeLines(cell);
        MasterProduct master = cell.getMasterProduct();
        String categoryCode = masterChannelConfigService.resolveChannelCategory(cell).category().getCode();
        String displayCategory = displayCategory(categoryCode, key);

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("trGrpCd", key.trGrpCd());
        product.put("trNo", key.trNo());
        if (register) {
            product.put("scatNo", categoryCode);
        } else {
            product.put("spdNo", cell.getPlatformProductId());
        }
        product.put("dcatLst", List.of(Map.of("mallCd", MALL_CODE, "lfDcatNo", displayCategory)));
        if (register) {
            product.put("epdNo", String.valueOf(cell.getId()));
            product.put("slTypCd", "GNRL");
            product.put("pdTypCd", "GNRL_GNRL");
        }
        product.put("spdNm", productName(cell, lines));
        product.put("oplcCd", "ETC");
        product.put("tdfDvsCd", "01");
        if (register) {
            product.put("slStrtDttm", LocalDateTime.now(SyncWindow.KST).format(LOTTEON_TIME));
            product.put("slEndDttm", "29991231235959");
        }
        LotteonNoticeConverter.Notification notice = LotteonNoticeConverter.convert(
                master == null ? null : master.getCategoryNoticeGroup(),
                master == null ? null : master.getCategoryNotices());
        List<Map<String, Object>> articles = new ArrayList<>();
        for (LotteonNoticeConverter.Line line : notice.lines()) {
            articles.add(Map.of("pdArtlCd", line.code(), "pdArtlCnts", line.value()));
        }
        product.put("pdItmsInfo", Map.of("pdItmsCd", notice.itemGroupCode(), "pdItmsArtlLst", articles));
        product.put("purPsbQtyInfo", Map.of("itmByMinPurYn", "N", "itmByMaxPurPsbQtyYn", "N"));
        if (register) {
            product.put("ageLmtCd", "0");
        }
        product.put("prstPckPsbYn", "N");
        product.put("prstMsgPsbYn", "N");
        if (register) {
            product.put("pdStatCd", "NEW");
        }
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("pdEpnTypCd", "DSCRP");
        description.put("cnts", gen == null ? null : gen.getDetailHtml());
        product.put("epnLst", List.of(description));
        if (register) {
            product.put("dvProcTypCd", "LO_ENTP");
        }
        product.put("dvPdTypCd", "GNRL");
        product.put("dvRgsprGrpCd", "GN000");
        product.put("dvMnsCd", "DPCL");
        product.put("owhpNo", shipping.get(LotteonShippingKeys.OWHP_NO));
        product.put("rtrpNo", shipping.get(LotteonShippingKeys.RTRP_NO));
        product.put("dvCstPolNo", shipping.get(LotteonShippingKeys.DV_CST_POL_NO));
        product.put("stkMgtYn", "Y");
        boolean hasOptions = lines.size() > 1;
        if (register) {
            product.put("sitmYn", hasOptions ? "Y" : "N");
        }
        product.put("itmLst", items(cell, gen, lines, hasOptions, register));
        if (register) {
            List<String> tags = tagMergeService.resolveTags(cell);
            if (!tags.isEmpty()) {
                product.put("scKwdLst", tags);
            }
        }
        return product;
    }

    /**
     * D21 · D22 · D24 · D52: one item per active option. Register = every item in the D21 shape. Update = an option
     * LotteON already numbered sends {@code sitmNo} + photos + price + stock only (no option name); a new option is
     * added in the D21 shape.
     */
    private List<Map<String, Object>> items(ProductListing cell, GeneratedProductData gen, List<Line> lines,
                                            boolean hasOptions, boolean register) {
        Map<Long, List<ListingMarketImage>> photosByOption = marketImageRepository
                .findByProductListingIdOrderBySortOrderAsc(cell.getId()).stream()
                .filter(i -> i.getProductListingOptionId() != null)
                .collect(Collectors.groupingBy(ListingMarketImage::getProductListingOptionId,
                        LinkedHashMap::new, Collectors.toList()));
        String title = optionTitle(lines);
        List<Map<String, Object>> items = new ArrayList<>();
        int sortSeq = 1;
        for (Line line : lines) {
            ProductListingOption option = line.option();
            Map<String, Object> item = new LinkedHashMap<>();
            boolean known = !register && StringUtils.hasText(option.getPlatformOptionId());
            if (known) {
                item.put("sitmNo", option.getPlatformOptionId());
            } else {
                item.put("eitmNo", String.valueOf(option.getId()));
                item.put("sortSeq", sortSeq);
                if (hasOptions) {
                    item.put("itmOptLst", List.of(Map.of("optNm", title, "optVal", option.getOptionName())));
                }
            }
            item.put("itmImgLst", itemImages(gen, photosByOption.getOrDefault(option.getId(), List.of())));
            item.put("slPrc", line.price());
            item.put("stkQty", line.stock());
            items.add(item);
            sortSeq++;
        }
        return items;
    }

    /**
     * D52: first = the representative — the option's stored market main photo when the thumbnail comes from the
     * market, else the listing thumbnail; then the option's other market photos in market order; 10 at most.
     */
    private static List<Map<String, Object>> itemImages(GeneratedProductData gen, List<ListingMarketImage> photos) {
        boolean marketThumbnail = gen != null && gen.getThumbnailSource() == GeneratedContentSource.MARKET;
        String representation = gen != null ? gen.getThumbnailUrl() : null;
        String representationKind = null;
        if (marketThumbnail) {
            for (ListingMarketImage photo : photos) {
                if (Boolean.TRUE.equals(photo.getRepresentative())) {
                    representation = photo.getImageUrl();
                    representationKind = photo.getMarketImageType();
                    break;
                }
            }
        }
        List<Map<String, Object>> images = new ArrayList<>();
        images.add(image(representation, representationKind, true));
        for (ListingMarketImage photo : photos) {
            if (images.size() >= MAX_ITEM_IMAGES) {
                break;
            }
            if (!Boolean.TRUE.equals(photo.getRepresentative())) {
                images.add(image(photo.getImageUrl(), photo.getMarketImageType(), false));
            }
        }
        return images;
    }

    private static Map<String, Object> image(String url, String kind, boolean representative) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("epsrTypCd", "IMG");
        image.put("epsrTypDtlCd", StringUtils.hasText(kind) ? kind : DEFAULT_IMAGE_KIND);
        image.put("origImgFileNm", url);
        image.put("rprtImgYn", representative ? "Y" : "N");
        return image;
    }

    /** D11: the first {@code LTON} display category 205 attaches to the standard category (response order). */
    private String displayCategory(String standardCategory, LotteonKey key) {
        JsonNode page = client.getCategory(LotteonPaths.STANDARD_CATEGORY + "&filter_1=" + standardCategory,
                key.apiKey());
        for (JsonNode item : page.path("itemList")) {
            for (JsonNode display : item.path("data").path("disp_list")) {
                if (MALL_CODE.equals(display.path("mall_dvs_cd").asText())
                        && !display.path("disp_cat_id").asText("").isBlank()) {
                    return display.path("disp_cat_id").asText();
                }
            }
        }
        throw new IllegalArgumentException(NO_DISPLAY_CATEGORY);
    }

    /** One 94 call → {@code data}; a failure or an answer without {@code data} = 502 「{prefix}{LotteON text}」. */
    private JsonNode readDetail(String spdNo, MarketplaceAccount acct, String failurePrefix) {
        LotteonKey key = LotteonCredentials.of(acct);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("trGrpCd", key.trGrpCd());
        request.put("trNo", key.trNo());
        request.put("spdNo", spdNo);
        JsonNode body;
        try {
            body = client.post(LotteonPaths.PRODUCT_DETAIL, request, key.apiKey());
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.BAD_GATEWAY) {
                throw new BusinessException(failurePrefix + e.getMessage(), HttpStatus.BAD_GATEWAY);
            }
            throw e;
        }
        JsonNode data = body.path("data");
        if (!data.isObject()) {
            throw new BusinessException(failurePrefix + LotteonApiClient.messageOf(body, 200), HttpStatus.BAD_GATEWAY);
        }
        return data;
    }

    /**
     * D25: either approval {@code GVBK} → REJECTED; else {@code fnlAprvYn} ≠ Y → SUBMITTED; else {@code SALE} ·
     * {@code SOUT} → SELLING, {@code STP} · {@code END} → SUSPENDED; anything else → null.
     */
    static ListingStatus fold(String saleStatus, JsonNode approval) {
        if ("GVBK".equals(approval.path("catAprvStatCd").asText())
                || "GVBK".equals(approval.path("pdInfoAprvStatCd").asText())) {
            return ListingStatus.REJECTED;
        }
        if (!"Y".equals(approval.path("fnlAprvYn").asText())) {
            return ListingStatus.SUBMITTED;
        }
        return switch (saleStatus) {
            case "SALE", "SOUT" -> ListingStatus.SELLING;
            case "STP", "END" -> ListingStatus.SUSPENDED;
            default -> null;
        };
    }

    private static String statusName(ListingStatus status, String saleStatus) {
        return switch (status) {
            case REJECTED -> REJECTED_NAME;
            case SUBMITTED -> WAITING_NAME;
            default -> SALE_STATUS_NAMES.get(saleStatus);
        };
    }

    /** D26: the category and product-information rejection texts that exist, joined with 「 / 」. */
    private static String rejectionText(JsonNode approval) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of("catGvbkRsnCnts", "pdInfoGvbkRsnCnts")) {
            String text = approval.path(field).asText("");
            if (!text.isBlank()) {
                parts.add(text.trim());
            }
        }
        return String.join(" / ", parts);
    }

    /**
     * D51: no options → the product name; options → the option values joined with 「 / 」 → else the item name → else
     * the product name.
     */
    private static String itemName(JsonNode item, boolean hasOptions, String productName) {
        if (!hasOptions) {
            return productName;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode option : item.path("itmOptLst")) {
            String value = option.path("optVal").asText("").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (!values.isEmpty()) {
            return String.join(" / ", values);
        }
        String itemName = item.path("sitmNm").asText("");
        return itemName.isBlank() ? productName : itemName;
    }

    /** D18: listing name, else the registration name; cut at 150 characters. */
    private String productName(ProductListing cell, List<Line> lines) {
        String name = cell.getName();
        if (!StringUtils.hasText(name) && cell.getMasterProduct() != null) {
            name = registrationNameGenerator.generate(cell.getMasterProduct(),
                    lines.stream().map(Line::option).toList(), optionCheckSuffixResolver.resolve(cell));
        }
        if (name == null || name.length() <= MAX_PRODUCT_NAME_LENGTH) {
            return name;
        }
        return name.substring(0, MAX_PRODUCT_NAME_LENGTH);
    }

    /** D21 / 11st D103: the per-option attribute names of the active options joined with 「/」; cut at 200. */
    private static String optionTitle(List<Line> lines) {
        Set<String> keys = new LinkedHashSet<>();
        for (Line line : lines) {
            if (line.master() != null && line.master().getCategoryAttributes() != null) {
                keys.addAll(line.master().getCategoryAttributes().keySet());
            }
        }
        String title = keys.isEmpty() ? DEFAULT_OPTION_TITLE : String.join("/", keys);
        return title.length() <= MAX_OPTION_TITLE_LENGTH ? title : title.substring(0, MAX_OPTION_TITLE_LENGTH);
    }

    /** D42: 「배송설정 미완료 — 누락 필드: 출고지 / 회수지 / 배송비 정책」 (missing ones only). */
    private static void requireShipping(Map<String, String> shipping) {
        List<String> missing = LotteonShippingResolver.missingLabels(shipping);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(SHIPPING_MISSING_PREFIX + String.join(" / ", missing));
        }
    }

    /** Active options in stored order, each with its master option and resolved stock (D21). */
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
            BigDecimal price = option.getSellingPrice() == null ? BigDecimal.ZERO : option.getSellingPrice();
            lines.add(new Line(option, linked, price, ListingStockPolicy.resolve(option, linked)));
        }
        return lines;
    }

    /** A number written by LotteON, or null when it is absent or not a number. */
    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText().trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Line(ProductListingOption option, MasterProductOption master, BigDecimal price, int stock) {
    }
}
