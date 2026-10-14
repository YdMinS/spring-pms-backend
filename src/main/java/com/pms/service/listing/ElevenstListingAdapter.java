package com.pms.service.listing;

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
import com.pms.service.ListingTextValueResolver;
import com.pms.service.MasterChannelConfigService;
import com.pms.service.OptionCheckSuffixResolver;
import com.pms.service.RegistrationNameGenerator;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstListingRules;
import com.pms.service.elevenst.ElevenstNoticeConverter;
import com.pms.service.elevenst.ElevenstXml;
import com.pms.service.listing.shipping.ElevenstShippingKeys;
import com.pms.service.listing.shipping.ElevenstShippingResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 11st {@link ListingChannel} adapter (FEATURE_2610_13 / D1) — HTTP only, no cell state changes.
 *
 * <p>Register = 1003 {@code POST /rest/prodservices/product} · update = 1619 {@code PUT …/product/{prdNo}} with the
 * same full body (D56) · status = 1007 {@code POST /rest/prodmarketservice/prodmarket} (D96) · stop = 1631
 * {@code PUT /rest/prodstatservice/stat/stopdisplay/{prdNo}} (D96). Bodies are EUC-KR XML ({@link ElevenstXml}).
 * The review-note lookup keeps the interface default (no call, D58); price push and market import stay
 * unsupported (D9 ⑤ · D8).</p>
 *
 * <p>Value sources: name D108 · options D55 · D62 · D81 · D90 · D103 · shipping D84~D92 · D106 · notices D89 · D100
 * · D102 · texts D69 · D73 · certification D94 · D113 · fixed codes D63 · D64 · D95.</p>
 */
@Component
@RequiredArgsConstructor
public class ElevenstListingAdapter implements ListingChannel {

    static final String PRODUCT_PATH = "/rest/prodservices/product";
    static final String STATUS_PATH = "/rest/prodmarketservice/prodmarket";
    static final String STOP_PATH = "/rest/prodstatservice/stat/stopdisplay/";

    static final String MISSING_NOTICE_MESSAGE = "A/S 안내 · 반품/교환 안내를 넣으세요";
    static final String MISSING_BRAND_MESSAGE = "브랜드를 넣으세요";
    static final String RULE_BLOCK_PREFIX = "11번가 규칙에 맞지 않아 올릴 수 없습니다: ";

    /** D108: 11st caps {@code prdNm} at 100 characters. */
    private static final int MAX_PRODUCT_NAME_LENGTH = 100;
    /** D103: option group title when no active option carries a per-option attribute name. */
    private static final String DEFAULT_OPTION_TITLE = "옵션";
    /** D64: origin text (exact wording, no space). */
    private static final String ORIGIN_TEXT = "상세설명 참조";

    /** D59: 11st {@code selStatCd} → our status. */
    private static final Map<String, ListingStatus> STATUS_BY_CODE = Map.ofEntries(
            Map.entry("101", ListingStatus.SUBMITTED),
            Map.entry("102", ListingStatus.SUBMITTED),
            Map.entry("103", ListingStatus.SELLING),
            Map.entry("104", ListingStatus.SELLING),
            Map.entry("110", ListingStatus.SELLING),
            Map.entry("109", ListingStatus.REJECTED),
            Map.entry("105", ListingStatus.SUSPENDED),
            Map.entry("106", ListingStatus.SUSPENDED),
            Map.entry("107", ListingStatus.SUSPENDED),
            Map.entry("108", ListingStatus.SUSPENDED));

    /** D59: 11st's own status wording, kept as {@code statusName}. */
    private static final Map<String, String> STATUS_NAME_BY_CODE = Map.ofEntries(
            Map.entry("101", "승인대기"),
            Map.entry("102", "승인전"),
            Map.entry("103", "판매중"),
            Map.entry("104", "품절"),
            Map.entry("105", "전시중지"),
            Map.entry("106", "판매정상종료"),
            Map.entry("107", "판매강제종료"),
            Map.entry("108", "판매금지"),
            Map.entry("109", "승인거부"),
            Map.entry("110", "승인완료"));

    /** D94 · D113: four certification groups (group code, target code), always sent. */
    private static final List<String[]> CERT_GROUPS = List.of(
            new String[]{"01", "03"},
            new String[]{"02", "03"},
            new String[]{"03", "03"},
            new String[]{"04", "05"});

    private final ElevenstApiClient client;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MasterChannelConfigService masterChannelConfigService;
    private final RegistrationNameGenerator registrationNameGenerator;
    private final OptionCheckSuffixResolver optionCheckSuffixResolver;
    private final ElevenstShippingResolver shippingResolver;
    private final ListingTextValueResolver textValueResolver;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    /** D68 → D69 → D66 (duplicate name → total stock 0 → price ratio), first failure wins. */
    @Override
    public void validateRegistrable(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        requireTexts(cell);
        List<Line> lines = activeLines(cell);
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
        BigDecimal min = minPrice(lines);
        Line highest = lines.get(0);
        for (Line line : lines) {
            if (line.price().compareTo(highest.price()) > 0) {
                highest = line;
            }
        }
        if (highest.price().compareTo(min.multiply(BigDecimal.valueOf(2))) > 0) {
            throw new IllegalArgumentException(RULE_BLOCK_PREFIX + "가장 비싼 옵션(" + highest.option().getOptionName()
                    + " " + won(highest.price()) + "원)이 가장 싼 옵션 가격의 두 배를 넘습니다");
        }
    }

    @Override
    public boolean isShippingReady(ProductListing cell) {
        return ElevenstShippingResolver.missingLabels(shippingResolver.resolve(cell)).isEmpty();
    }

    @Override
    public String register(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        byte[] raw = client.post(PRODUCT_PATH, buildPayload(cell, gen), acct);
        Document doc = ElevenstXml.parseOrNull(raw);
        String resultCode = ElevenstXml.firstText(doc, "resultCode");
        String productNo = ElevenstXml.firstText(doc, "productNo");
        if (("200".equals(resultCode) || "210".equals(resultCode)) && StringUtils.hasText(productNo)) {
            return productNo;
        }
        // D60: 11st's own wording is the failure reason.
        throw new BusinessException(ElevenstXml.messageOf(raw), HttpStatus.BAD_GATEWAY);
    }

    @Override
    public void update(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct) {
        byte[] raw = client.put(PRODUCT_PATH + "/" + cell.getPlatformProductId(), buildPayload(cell, gen), acct);
        requireSuccess(raw);
    }

    /** D96 · D59 · D107: on SELLING every active option is reported under its name with our option id. */
    @Override
    public FetchResult fetchStatus(ProductListing cell, MarketplaceAccount acct) {
        StringBuilder body = new StringBuilder("<SearchProduct>");
        ElevenstXml.element(body, "prdNo", cell.getPlatformProductId());
        body.append("</SearchProduct>");
        byte[] raw = client.post(STATUS_PATH, body.toString(), acct);
        String code = ElevenstXml.firstText(ElevenstXml.parseOrNull(raw), "selStatCd");
        ListingStatus status = code == null ? null : STATUS_BY_CODE.get(code);
        if (status == null) {
            throw new BusinessException("11번가 상태를 읽지 못했습니다: " + ElevenstXml.messageOf(raw),
                    HttpStatus.BAD_GATEWAY);
        }
        List<FetchResult.OptionId> options = new ArrayList<>();
        if (status == ListingStatus.SELLING) {
            for (ProductListingOption option : productListingOptionRepository.findByProductListingId(cell.getId())) {
                if (Boolean.TRUE.equals(option.getActive())) {
                    options.add(new FetchResult.OptionId(
                            option.getOptionName(), String.valueOf(option.getId()), null));
                }
            }
        }
        return new FetchResult(status, options, STATUS_NAME_BY_CODE.get(code));
    }

    @Override
    public void delete(ProductListing cell, MarketplaceAccount acct) {
        requireSuccess(client.put(STOP_PATH + cell.getPlatformProductId(), null, acct));
    }

    /** The {@code <Product>} body sent by register and update alike (D56). */
    String buildPayload(ProductListing cell, GeneratedProductData gen) {
        Texts texts = requireTexts(cell);
        List<Line> lines = activeLines(cell);
        Map<String, String> shipping = shippingResolver.resolve(cell);
        List<String> missing = ElevenstShippingResolver.missingLabels(shipping);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("배송설정 미완료 — 누락 필드: " + String.join(", ", missing));
        }
        MasterProduct master = cell.getMasterProduct();
        String categoryCode = masterChannelConfigService.resolveChannelCategory(cell).category().getCode();

        StringBuilder xml = new StringBuilder("<Product>");
        ElevenstXml.element(xml, "selMthdCd", "01");
        ElevenstXml.element(xml, "dispCtgrNo", categoryCode);
        ElevenstXml.element(xml, "prdTypCd", "01");
        ElevenstXml.element(xml, "prdNm", productName(cell, lines));
        ElevenstXml.element(xml, "brand", texts.brand());
        ElevenstXml.element(xml, "rmaterialTypCd", "05");
        ElevenstXml.element(xml, "orgnTypCd", "03");
        ElevenstXml.element(xml, "orgnNmVal", ORIGIN_TEXT);
        ElevenstXml.element(xml, "suplDtyfrPrdClfCd", "01");
        ElevenstXml.element(xml, "prdStatCd", "01");
        ElevenstXml.element(xml, "minorSelCnYn", "Y");
        ElevenstXml.element(xml, "prdImage01", gen == null ? null : gen.getThumbnailUrl());
        ElevenstXml.cdata(xml, "htmlDetail", gen == null ? null : gen.getDetailHtml());
        for (String[] group : CERT_GROUPS) {
            xml.append("<ProductCertGroup>");
            ElevenstXml.element(xml, "crtfGrpTypCd", group[0]);
            ElevenstXml.element(xml, "crtfGrpObjClfCd", group[1]);
            xml.append("</ProductCertGroup>");
        }
        xml.append("<ProductCert>");
        ElevenstXml.element(xml, "certTypeCd", "131");
        ElevenstXml.element(xml, "certKey", "");
        xml.append("</ProductCert>");

        BigDecimal min = minPrice(lines);
        ElevenstXml.element(xml, "selPrc", won(min));
        ElevenstXml.element(xml, "prdSelQty", lines.stream().mapToLong(Line::stock).sum());
        ElevenstXml.element(xml, "prcCmpExpYn", "Y");
        if (lines.size() > 1) {
            ElevenstXml.element(xml, "optSelectYn", "Y");
            ElevenstXml.element(xml, "txtColCnt", "1");
            ElevenstXml.element(xml, "colTitle", optionTitle(lines));
            ElevenstXml.element(xml, "prdExposeClfCd", "01");
            for (Line line : lines) {
                xml.append("<ProductOption>");
                ElevenstXml.element(xml, "useYn", "Y");
                ElevenstXml.element(xml, "colOptPrice", won(line.price().subtract(min)));
                ElevenstXml.element(xml, "colValue0", line.option().getOptionName());
                ElevenstXml.element(xml, "colCount", line.stock());
                ElevenstXml.element(xml, "colSellerStockCd", line.option().getId());
                xml.append("</ProductOption>");
            }
        }
        appendShipping(xml, shipping);
        ElevenstXml.element(xml, "asDetail", texts.asNotice());
        ElevenstXml.element(xml, "rtngExchDetail", texts.returnExchangeNotice());

        ElevenstNoticeConverter.Notification notification = ElevenstNoticeConverter.convert(
                master == null ? null : master.getCategoryNoticeGroup(),
                master == null ? null : master.getCategoryNotices());
        xml.append("<ProductNotification>");
        ElevenstXml.element(xml, "type", notification.typeCode());
        for (ElevenstNoticeConverter.Line line : notification.lines()) {
            xml.append("<item>");
            ElevenstXml.element(xml, "code", line.code());
            ElevenstXml.element(xml, "name", line.value());
            xml.append("</item>");
        }
        xml.append("</ProductNotification>");
        return xml.append("</Product>").toString();
    }

    /** D84 · D91 · D106: the shipping fields; fee amounts only for the chosen fee type. */
    private static void appendShipping(StringBuilder xml, Map<String, String> s) {
        String method = value(s, ElevenstShippingKeys.DLV_WY_CD);
        String feeType = value(s, ElevenstShippingKeys.DLV_CST_INST_BASI_CD);
        String payType = value(s, ElevenstShippingKeys.DLV_CST_PAY_TYP_CD);
        ElevenstXml.element(xml, "dlvCnAreaCd", value(s, ElevenstShippingKeys.DLV_CN_AREA_CD));
        ElevenstXml.element(xml, "dlvWyCd", method);
        if ("01".equals(method)) {
            ElevenstXml.element(xml, "dlvEtprsCd", value(s, ElevenstShippingKeys.DLV_ETPRS_CD));
        }
        ElevenstXml.element(xml, "dlvCstInstBasiCd", feeType);
        switch (feeType) {
            case "02" -> ElevenstXml.element(xml, "dlvCst1", value(s, ElevenstShippingKeys.DLV_CST1));
            case "03" -> {
                ElevenstXml.element(xml, "dlvCst1", value(s, ElevenstShippingKeys.DLV_CST1));
                ElevenstXml.element(xml, "PrdFrDlvBasiAmt", value(s, ElevenstShippingKeys.PRD_FR_DLV_BASI_AMT));
            }
            case "04" -> {
                ElevenstXml.element(xml, "dlvCst3", value(s, ElevenstShippingKeys.DLV_CST3));
                ElevenstXml.element(xml, "dlvCnt1", value(s, ElevenstShippingKeys.DLV_CNT1));
                ElevenstXml.element(xml, "dlvCnt2", value(s, ElevenstShippingKeys.DLV_CNT2));
            }
            case "05" -> ElevenstXml.element(xml, "dlvCst4", value(s, ElevenstShippingKeys.DLV_CST4));
            default -> {
                // 01 free · 07 seller policy · 08 outbound-place policy: no amount is sent
            }
        }
        ElevenstXml.element(xml, "dlvCstPayTypCd", payType);
        if ("02".equals(feeType) && "02".equals(payType)) {
            ElevenstXml.element(xml, "dlvCstInfoCd", value(s, ElevenstShippingKeys.DLV_CST_INFO_CD));
        }
        ElevenstXml.element(xml, "bndlDlvCnYn", value(s, ElevenstShippingKeys.BNDL_DLV_CN_YN));
        ElevenstXml.element(xml, "jejuDlvCst", value(s, ElevenstShippingKeys.JEJU_DLV_CST));
        ElevenstXml.element(xml, "islandDlvCst", value(s, ElevenstShippingKeys.ISLAND_DLV_CST));
        ElevenstXml.element(xml, "addrSeqOut", value(s, ElevenstShippingKeys.ADDR_SEQ_OUT));
        ElevenstXml.element(xml, "addrSeqIn", value(s, ElevenstShippingKeys.ADDR_SEQ_IN));
        ElevenstXml.element(xml, "rtngdDlvCst", value(s, ElevenstShippingKeys.RTNGD_DLV_CST));
        ElevenstXml.element(xml, "exchDlvCst", value(s, ElevenstShippingKeys.EXCH_DLV_CST));
        ElevenstXml.element(xml, "rtngdDlvCd", value(s, ElevenstShippingKeys.RTNGD_DLV_CD));
        ElevenstXml.element(xml, "dlvClf", "02");
    }

    /** D108: listing name, else the registration name; cut at 100 characters. */
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

    /** D103: the per-option attribute names of the active options joined with 「/」, made 11st-safe. */
    private static String optionTitle(List<Line> lines) {
        Set<String> keys = new LinkedHashSet<>();
        for (Line line : lines) {
            if (line.master() != null && line.master().getCategoryAttributes() != null) {
                keys.addAll(line.master().getCategoryAttributes().keySet());
            }
        }
        String title = keys.isEmpty() ? DEFAULT_OPTION_TITLE : String.join("/", keys);
        return ElevenstListingRules.sanitizeName(title);
    }

    /** D68 · D69: both notices and the brand must resolve to text. */
    private Texts requireTexts(ProductListing cell) {
        String asNotice = textValueResolver.notice(cell, ListingTextValueResolver.KEY_AS_NOTICE);
        String returnNotice = textValueResolver.notice(cell, ListingTextValueResolver.KEY_RETURN_EXCHANGE_NOTICE);
        if (asNotice == null || returnNotice == null) {
            throw new IllegalArgumentException(MISSING_NOTICE_MESSAGE);
        }
        String brand = textValueResolver.brand(cell);
        if (brand == null) {
            throw new IllegalArgumentException(MISSING_BRAND_MESSAGE);
        }
        return new Texts(brand, asNotice, returnNotice);
    }

    /** Active options in stored order, each with its master option and resolved stock (D55). */
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

    private static BigDecimal minPrice(List<Line> lines) {
        return lines.stream().map(Line::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private static String won(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String value(Map<String, String> settings, String key) {
        String value = settings.get(key);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** A 2xx body that still carries a failure {@code resultCode} is a failure (D60 wording). */
    private static void requireSuccess(byte[] raw) {
        String resultCode = ElevenstXml.firstText(ElevenstXml.parseOrNull(raw), "resultCode");
        if (resultCode != null && !"200".equals(resultCode)) {
            throw new BusinessException(ElevenstXml.messageOf(raw), HttpStatus.BAD_GATEWAY);
        }
    }

    private record Line(ProductListingOption option, MasterProductOption master, BigDecimal price, int stock) {
    }

    private record Texts(String brand, String asNotice, String returnExchangeNotice) {
    }
}
