package com.pms.service.lotteon;

/**
 * LotteON Open API paths (FEATURE_2610_24 — public guide numbers in brackets). Appended to
 * {@code oklyx.lotteon.base-url}; the standard category lookup goes to {@code category-base-url}.
 */
public final class LotteonPaths {

    /** [207] the key's own seller group and seller number. */
    public static final String IDENTITY = "/v1/openapi/common/v1/identity";
    /** [87] product registration. */
    public static final String REGISTER = "/v1/openapi/product/v1/product/registration/request";
    /** [90] approved product modification. */
    public static final String MODIFY = "/v1/openapi/product/v1/product/modification/request";
    /** [92] product sale status change. */
    public static final String PRODUCT_STATUS = "/v1/openapi/product/v1/product/status/change";
    /** [111] item sale status change. */
    public static final String ITEM_STATUS = "/v1/openapi/product/v1/item/status/change";
    /** [93] product list. */
    public static final String PRODUCT_LIST = "/v1/openapi/product/v1/product/list";
    /** [94] product detail. */
    public static final String PRODUCT_DETAIL = "/v1/openapi/product/v1/product/detail";
    /** [150] outbound / return place list. */
    public static final String PLACES = "/v1/openapi/contract/v1/dvp/getDvpListSr";
    /** [166] shipping fee policy list. */
    public static final String FEE_POLICIES = "/v1/openapi/contract/v1/dvl/getDvCstListSr";
    /** [205] standard category lookup (category host). */
    public static final String STANDARD_CATEGORY = "/cheetah/econCheetah.ecn?job=cheetahStandardCategory";

    private LotteonPaths() {
    }
}
