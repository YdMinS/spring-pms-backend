package com.pms.service.elevenst;

import java.util.Map;

/**
 * Paths of the 11st order · claim · inquiry APIs this feature calls (FEATURE_2610_17 / D29, guide numbers in
 * comments). Every path is appended to {@code oklyx.elevenst.base-url} by {@link ElevenstApiClient}.
 *
 * <p>Placeholders are {@code {name}} and are filled by {@link #fill} only — never leave one unfilled: the client
 * would read it as a URI template variable.
 */
public final class ElevenstPaths {

    // ---- orders (D6 · D29) ----
    /** 1876 paid orders. */
    public static final String ORDERS_PAID = "/rest/ordservices/complete/{start}/{end}";
    /** 1635 orders whose order confirmation is done (preparing). */
    public static final String ORDERS_PREPARING = "/rest/ordservices/packaging/{start}/{end}";
    /** 6897 orders in delivery. */
    public static final String ORDERS_SHIPPING = "/rest/ordservices/shipping/{start}/{end}";
    /** 1727 delivered orders. */
    public static final String ORDERS_DELIVERED = "/rest/ordservices/dlvcompleted/{start}/{end}";
    /** 1634 order confirmation (D20 · D48). */
    public static final String ACKNOWLEDGE =
            "/rest/ordservices/reqpackaging/{ordNo}/{ordPrdSeq}/{addPrdYn}/{addPrdNo}/{dlvNo}";
    /** 1888 shipping (invoice registration, D2). */
    public static final String SHIP =
            "/rest/ordservices/reqdelivery/{sendDt}/{dlvMthdCd}/{dlvEtprsCd}/{invcNo}/{dlvNo}";

    // ---- cancels (D14 · D40) ----
    /** 1639 cancel requests. */
    public static final String CANCEL_REQUESTS = "/rest/claimservice/cancelorders/{start}/{end}";
    /** 1642 completed cancels. */
    public static final String CANCEL_COMPLETED = "/rest/claimservice/canceledorders/{start}/{end}";
    /** 1724 withdrawn cancels. */
    public static final String CANCEL_WITHDRAWN = "/rest/claimservice/withdrawcanceledorders/{start}/{end}";
    /** 1640 cancel approve. */
    public static final String CANCEL_APPROVE = "/rest/claimservice/cancelreqconf/{ordPrdCnSeq}/{ordNo}/{ordPrdSeq}";
    /** 1641 cancel reject (invoice required, D38). */
    public static final String CANCEL_REJECT = "/rest/claimservice/cancelreqreject/{ordNo}/{ordPrdSeq}/{ordPrdCnSeq}"
            + "/{dlvMthdCd}/{sendDt}/{dlvEtprsCd}/{invcNo}";

    private ElevenstPaths() {
    }

    /** Replaces every {@code {key}} of the template with its value as given (callers encode text values first). */
    public static String fill(String template, Map<String, String> values) {
        String path = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        if (path.contains("{")) {
            throw new IllegalStateException("11번가 경로 자리표시자가 남았습니다: " + path);
        }
        return path;
    }
}
