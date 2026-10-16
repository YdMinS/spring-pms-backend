package com.pms.domain;

/**
 * 회수 송장 값의 출처 (FEATURE_2609_70 / PLAN D12).
 *
 * <p>{@code null}(기존 행)은 출처 불명이며 <b>플랫폼 값으로 취급</b>한다 — 백필이 없으므로 컬럼이
 * 생기기 전에 동기화로 들어온 송장은 전부 여기에 속한다. 재전송은 {@link #LOCAL} 에서만 열린다(D7).
 */
public enum CollectInvoiceSource {

    /** Read from the marketplace response by the sync (Coupang · 11st) — it is on the marketplace too. */
    PLATFORM,

    /**
     * The marketplace rejected the registration and only our ledger kept it (D6) — the resend target (D7):
     * [쿠팡에 다시 보내기] on Coupang, [마켓에 다시 보내기] on 11st (FEATURE_2610_17 / D22).
     */
    LOCAL
}
