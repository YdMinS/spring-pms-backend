package com.pms.dto.response;

import java.util.List;

/**
 * 알림 목록 한 장 (GET /api/alerts, FEATURE_2609_51 / PLAN D12).
 *
 * @param items      최신순 행들 — {@code 발생시각 desc · 종류 asc · id desc} 하나로 고정된 순서다
 * @param nextCursor 다음 장을 요청할 때 그대로 돌려보내는 값. <b>null 이면 끝</b>이다.
 *                   형식은 {@code 발생시각|종류|id} — 🔴 시각만으로는 부족하다(같은 시각에 두 건이
 *                   들어오면 한 건이 중복되거나 빠진다). 클라이언트는 <b>내용을 해석하지 않는다.</b>
 */
public record AlertFeedResponse(List<AlertFeedItemResponse> items, String nextCursor) {
}
