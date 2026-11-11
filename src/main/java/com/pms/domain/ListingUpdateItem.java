package com.pms.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The 12 items a Coupang revision compares and may overwrite on the product it reads from Coupang
 * (FEATURE_2611_25 / D6 · D21). Declaration order = the order the compare window lists them (D6).
 *
 * <p>Every other field of the product read from Coupang (sale period · unit count · purchase limits · shipping lead
 * time · adult · tax · parallel import · overseas purchase · customs code · certifications · {@code bundleInfo} and any
 * field we do not know) is always sent back as it was read (D6 · D20).</p>
 *
 * <p>{@code perOption = true} = the value lives on each Coupang option ({@code items[]}) and one check covers every
 * paired option (D19).</p>
 */
public enum ListingUpdateItem {

    REGISTRATION_NAME("등록상품명", false),
    DISPLAY_NAME("노출상품명", false),
    CATEGORY("카테고리", false),
    OPTION_NAME("옵션명", true),
    PRICE("판매가", true),
    STOCK("재고", true),
    IMAGES("사진", true),
    DETAIL("상세", false),
    SEARCH_TAGS("검색어", false),
    ATTRIBUTES("속성", true),
    NOTICES("고시", true),
    SHIPPING("배송·반품", false);

    /**
     * D9: the items a batch push (no compare window) overwrites, and a [수정 요청] without a body. The other six are
     * always sent as read from Coupang there.
     */
    public static final Set<ListingUpdateItem> BATCH_DEFAULT = Collections.unmodifiableSet(
            EnumSet.of(OPTION_NAME, PRICE, ATTRIBUTES, NOTICES, IMAGES, DETAIL));

    private final String label;
    private final boolean perOption;

    ListingUpdateItem(String label, boolean perOption) {
        this.label = label;
        this.perOption = perOption;
    }

    /** The screen name of the item (D6). */
    public String label() {
        return label;
    }

    /** True when the item is valued per Coupang option (D19). */
    public boolean perOption() {
        return perOption;
    }
}
