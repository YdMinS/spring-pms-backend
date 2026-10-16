package com.pms.service.elevenst;

import com.pms.domain.OrderStatus;

/**
 * The 11st order lists the sync reads (FEATURE_2610_17 / D6 · D29). 11st list rows carry no status field — the list
 * a row came from IS its status.
 *
 * <p>{@code createsLines} = the row carries quantity and amounts, so a new order line can be made from it. The
 * shipping and delivered lists only move lines that already exist.
 */
public enum ElevenstOrderList {

    /** 1876 — code 202 「결제완료」. */
    PAID(ElevenstPaths.ORDERS_PAID, "202", OrderStatus.PAID, true),
    /** 1635 — code 301 「발주확인」 (preparing). */
    PREPARING(ElevenstPaths.ORDERS_PREPARING, "301", OrderStatus.PREPARING, true),
    /** 6897 — code 401 「발송완료」 (in delivery). */
    SHIPPING(ElevenstPaths.ORDERS_SHIPPING, "401", OrderStatus.SHIPPED, false),
    /** 1727 — code 501 「배송완료」. */
    DELIVERED(ElevenstPaths.ORDERS_DELIVERED, "501", OrderStatus.DELIVERED, false);

    private final String path;
    private final String code;
    private final OrderStatus status;
    private final boolean createsLines;

    ElevenstOrderList(String path, String code, OrderStatus status, boolean createsLines) {
        this.path = path;
        this.code = code;
        this.status = status;
        this.createsLines = createsLines;
    }

    public String path() {
        return path;
    }

    /** Raw 11st status code stored in {@code elevenst_order_line.platform_status}. */
    public String code() {
        return code;
    }

    public OrderStatus status() {
        return status;
    }

    public boolean createsLines() {
        return createsLines;
    }
}
