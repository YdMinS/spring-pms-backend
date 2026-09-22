package com.pms.domain;

/**
 * 회수 송장 값의 출처 (FEATURE_2609_70 / PLAN D12).
 *
 * <p>{@code null}(기존 행)은 출처 불명이며 <b>플랫폼 값으로 취급</b>한다 — 백필이 없으므로 컬럼이
 * 생기기 전에 동기화로 들어온 송장은 전부 여기에 속한다. 재전송은 {@link #LOCAL} 에서만 열린다(D7).
 */
public enum CollectInvoiceSource {

    /** 동기화가 쿠팡 응답에서 읽어온 값 — 마켓에도 붙어 있다. */
    PLATFORM,

    /** 쿠팡이 등록을 거절해 우리 장부에만 남긴 값(D6) — [쿠팡에 다시 보내기] 대상(D7). */
    LOCAL
}
