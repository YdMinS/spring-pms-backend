package com.pms.service.lotteon;

/**
 * LotteON Open API paths (FEATURE_2610_24 · FEATURE_2611_23 — public guide numbers in brackets). Appended to
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
    /** [91] item selling price change (FEATURE_2611_19 / D3). */
    public static final String ITEM_PRICE = "/v1/openapi/product/v1/item/price/change";
    /** [86] item stock change (FEATURE_2611_19 / D5). */
    public static final String ITEM_STOCK = "/v1/openapi/product/v1/item/stock/change";
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
    /** [209] outbound / retrieval instruction list (FEATURE_2611_23 / D11). */
    public static final String DELIVERY_ORDERS = "/v1/openapi/delivery/v1/SellerDeliveryOrdersSearch";
    /** [210] instruction receipt inform (FEATURE_2611_23 / D16). */
    public static final String IF_COMPLETE = "/v1/openapi/delivery/v1/SellerIfCompleteInform";
    /** [140] delivery progress state list (FEATURE_2611_23 / D11 · D20). */
    public static final String PROGRESS_STATES = "/v1/openapi/delivery/v1/SellerDeliveryProgressStateSearch";
    /** [298] delivery progress state inform (FEATURE_2611_23 / D18). */
    public static final String PROGRESS_INFORM = "/v1/openapi/delivery/v2/SellerDeliveryProgressStateInform";
    /** [139] invoice number modification (FEATURE_2611_23 / D19). */
    public static final String INVOICE_MODIFY = "/v1/openapi/delivery/v1/SellerInvoiceNoModifyInform";
    /** [50] cancel request / completed / withdrawn list (FEATURE_2611_23 / D11 · R21). */
    public static final String CANCEL_LIST =
            "/v1/openapi/claim/v1/cancellationOpenApi/getCancellationRequestAndComplateList";
    /** [60] cancel request approval (FEATURE_2611_23 / D24). */
    public static final String CANCEL_APPROVE = "/v1/openapi/claim/v1/cancellationOpenApi/cnclRequestApproval";
    /** [59] cancel request rejection (FEATURE_2611_23 / D24). */
    public static final String CANCEL_REJECT = "/v1/openapi/claim/v1/cancellationOpenApi/cnclRequestHold";
    /** [225] seller direct cancel (FEATURE_2611_23 / D25). */
    public static final String SELLER_CANCEL = "/v1/openapi/claim/v1/cancellationOpenApi/slrDirectCnclProc";
    /** [138] shipping appointment date inform (FEATURE_2611_23 / D26). */
    public static final String DELIVERY_APPOINTMENT = "/v1/openapi/delivery/v1/SellerDeliveryAppointmentInform";
    /** [51] return request list (FEATURE_2611_23 / D27). */
    public static final String RETURN_LIST = "/v1/openapi/claim/v1/returningOpenApi/returnRequestSearch";
    /** [67] return withdrawal list (FEATURE_2611_23 / D27). */
    public static final String RETURN_WITHDRAW_LIST = "/v1/openapi/claim/v1/returningOpenApi/returnWithdrawSearch";
    /** [69] exchange request list (FEATURE_2611_23 / D27). */
    public static final String EXCHANGE_LIST = "/v1/openapi/claim/v1/exchangeOpenApi/exchangeSearch";
    /** [70] exchange withdrawal list (FEATURE_2611_23 / D27). */
    public static final String EXCHANGE_WITHDRAW_LIST = "/v1/openapi/claim/v1/exchangeOpenApi/exchangeWithdrawSearch";
    /** [141] retrieval exception (hold 03 · release 09) inform (FEATURE_2611_23 / D28 · D45). */
    public static final String RETRIEVAL_EXCEPTION = "/v1/openapi/delivery/v1/SellerRetrievalExceptionInform";
    /** [99] product Q&A list (FEATURE_2611_23 / D31 · D83). */
    public static final String QNA_LIST = "/v1/openapi/product/v1/product/qna/list";
    /** [100] product Q&A answer (FEATURE_2611_23 / D31). */
    public static final String QNA_REPLY = "/v1/openapi/product/v1/product/qna/reply";
    /** [179] seller inquiry list (FEATURE_2611_23 / D31 · D81). */
    public static final String SELLER_INQUIRY_LIST = "/v1/openapi/customer/v1/getSellerInquiryList";
    /** [181] seller inquiry answer (FEATURE_2611_23 / D31 · D81). */
    public static final String SELLER_INQUIRY_REPLY = "/v1/openapi/customer/v1/updateSellerInquiry";
    /** [144] seller contact list (FEATURE_2611_23 / D31 · D81 · D82). */
    public static final String SELLER_CONTACT_LIST = "/v1/openapi/customer/v1/getSellerContactList";
    /** [146] seller contact answer (FEATURE_2611_23 / D31 · D81). */
    public static final String SELLER_CONTACT_REPLY = "/v1/openapi/customer/v1/updateSellerContact";
    /** [68] non-receipt report list (FEATURE_2611_23 / D31 · D66). */
    public static final String NON_RECEIPT_LIST = "/v1/openapi/claim/v1/nonReceiptDeclareOpenApi/noReceiveSearch";
    /** [66] non-receipt report withdrawal request (FEATURE_2611_23 / D31). */
    public static final String NON_RECEIPT_WITHDRAW =
            "/v1/openapi/claim/v1/nonReceiptDeclareOpenApi/noReceiveCancelReq";

    private LotteonPaths() {
    }
}
