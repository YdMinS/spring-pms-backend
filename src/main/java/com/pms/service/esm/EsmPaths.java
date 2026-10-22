package com.pms.service.esm;

/**
 * ESM Trading API paths used by oclyx (FEATURE_2610_26 — guide posts in brackets). Appended to
 * {@code oklyx.esm.base-url}.
 */
public final class EsmPaths {

    /** [17] shipping places — {@code ?pageSize=&pageIndex=}, at most 500 per page. */
    public static final String SHIPPING_PLACES = "/item/v1/shipping/places";
    /** [18] bundle-fee policies of one shipping place — {@code /item/v1/shipping/places/{placeNo}/policies}. */
    public static final String PLACE_POLICIES = "/item/v1/shipping/places/%s/policies";
    /** [19] dispatch policies. */
    public static final String DISPATCH_POLICIES = "/item/v1/shipping/dispatch-policies";
    /** [16] seller address book — {@code ?pageSize=&pageIndex=}. */
    public static final String ADDRESSES = "/item/v1/sellers/addresses";
    /** [142] courier list. */
    public static final String DELIVERY_COMPANIES = "/item/v1/shipping/delivery-company";
    /** [5] ESM categories — {@code 0} = the top level, else the children of that code. */
    public static final String SD_CATEGORIES = "/item/v1/categories/sd-cats/%s";
    /** [13] the lowest Gmarket / Auction categories matched to one ESM leaf category. */
    public static final String SD_CATEGORY_SITE_CATS = "/item/v1/categories/sd-cats/%s/site-cats";
    /** [4] site categories — no code = the top level of the site in the token. */
    public static final String SITE_CATEGORIES = "/item/v1/categories/site-cats";
    /**
     * [20] register ({@code POST}) · update ({@code PUT .../{goodsNo}}) · read ({@code GET .../{goodsNo}},
     * FEATURE_2610_27 / D16).
     */
    public static final String GOODS = "/item/v1/goods";
    /** [21] price · stock · selling status — {@code /item/v1/goods/{goodsNo}/sell-status}. */
    public static final String SELL_STATUS = "/item/v1/goods/%s/sell-status";

    private EsmPaths() {
    }
}
