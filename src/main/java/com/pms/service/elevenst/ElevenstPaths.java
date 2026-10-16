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

    // ---- returns · exchanges (D15 · D22 · D23 · D24) ----
    /** 1643 return requests. */
    public static final String RETURN_REQUESTS = "/rest/claimservice/returnorders/{start}/{end}";
    /** 1646 completed returns ({@code trtEndDt} is not stored, D49). */
    public static final String RETURN_COMPLETED = "/rest/claimservice/returnedorders/{start}/{end}";
    /** 1647 withdrawn returns. */
    public static final String RETURN_WITHDRAWN = "/rest/claimservice/retractretorders/{start}/{end}";
    /** 1644 return approve (= return completed). */
    public static final String RETURN_APPROVE = "/rest/claimservice/returnreqconf/{clmReqSeq}/{ordNo}/{ordPrdSeq}";
    /** 1645 return reject. */
    public static final String RETURN_REJECT =
            "/rest/claimservice/returnreqreject/{ordNo}/{ordPrdSeq}/{clmReqSeq}/{refsRsnCd}/{refsRsn}";
    /** 1654 return hold. */
    public static final String RETURN_HOLD =
            "/rest/claimservice/returnclaimdefer/{ordNo}/{ordPrdSeq}/{clmReqSeq}/{deferRefsRsnCd}/{deferRefsRsn}";
    /** 1655 return-completion hold (same fields as 1654). */
    public static final String RETURN_COMPLETE_HOLD =
            "/rest/claimservice/returncompletedefer/{ordNo}/{ordPrdSeq}/{clmReqSeq}/{deferRefsRsnCd}/{deferRefsRsn}";
    /** 6747 return pickup invoice ({@code claimservice} twice — as the guide writes it). */
    public static final String RETURN_COLLECT_INVOICE = "/rest/claimservice/claimservice/returninvc"
            + "/{sendDt}/{dlvNo}/{dlvEtprsCd}/{invcNo}/{dlvMthdCd}/{clmReqSeq}";
    /** 1653 seller return request on the buyer's behalf (D35 · D36). */
    public static final String SELLER_RETURN = "/rest/claimservice/sellerclaimfix/{ordNo}/{ordPrdSeq}/{clmReqRsn}"
            + "/{claimProcess}/{dlvEtprsCd}/{invcNo}/{dlvMthdCd}/{clmReqCont}/{clmDlvCstMthd}";
    /** 1648 exchange requests. */
    public static final String EXCHANGE_REQUESTS = "/rest/claimservice/exchangeorders/{start}/{end}";
    /** 1651 completed exchanges ({@code trtEndDt} is not stored, D49). */
    public static final String EXCHANGE_COMPLETED = "/rest/claimservice/exchangedorders/{start}/{end}";
    /** 1652 withdrawn exchanges. */
    public static final String EXCHANGE_WITHDRAWN = "/rest/claimservice/retractexcorders/{start}/{end}";
    /** 1649 exchange approve with the re-shipping invoice (D24). */
    public static final String EXCHANGE_APPROVE =
            "/rest/claimservice/exchangereqconf/{clmReqSeq}/{ordNo}/{ordPrdSeq}/{dlvEtprsCd}/{invcNo}";
    /** 1650 exchange reject. */
    public static final String EXCHANGE_REJECT =
            "/rest/claimservice/exchangereqreject/{ordNo}/{ordPrdSeq}/{clmReqSeq}/{refsRsnCd}/{refsRsn}";

    // ---- inquiries (D16 · D26 · D44) ----
    /** 1626 product Q&A list ({@code yyyyMMdd} window, answerStatus 00 = all). */
    public static final String QNA_LIST = "/rest/prodqnaservices/prodqnalist/{start}/{end}/{answerStatus}";
    /** 1885 product Q&A answer (PUT). */
    public static final String QNA_ANSWER = "/rest/prodqnaservices/prodqnaanswer/{brdInfoNo}/{prdNo}";
    /**
     * 1796 urgent-notice list ({@code yyyyMMdd} window), one call per status code — the order-number segment is
     * left out, no trailing {@code /} (D50).
     */
    public static final String ALIMI_LIST = "/rest/alimi/getalimilist/{start}/{end}/{emerNtceCrntCd}";
    /** 6982 urgent-notice confirm / answer (PUT). */
    public static final String ALIMI_ANSWER = "/rest/alimi/alimianswer";

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
