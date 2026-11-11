package com.pms.service.lotteon;

import java.util.Map;

/**
 * The 「사유 이름 — 사유 글」 of a LotteON cancel request (FEATURE_2611_23 / D71 · R27). Names = the [50]
 * {@code clmRsnCd} table word for word (the 「(… 추가)」 tails dropped). The server builds the text; the screens
 * draw what they get.
 */
public final class LotteonCancelReasons {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("101", "배송이 늦어짐"),
            Map.entry("102", "상품이 품절됨"),
            Map.entry("103", "옵션/사이즈 불만 /취소"),
            Map.entry("104", "다른 곳보다 비쌈"),
            Map.entry("105", "쿠폰/할인혜택 변경"),
            Map.entry("106", "구매의사 없어짐"),
            Map.entry("107", "사은품 변경 / 취소"),
            Map.entry("108", "결제수단 변경(구매사은 적용/카드변경 등)"),
            Map.entry("109", "유사상품 구매"),
            Map.entry("110", "상품정보 미흡"),
            Map.entry("111", "판매자 취소(판매자)"),
            Map.entry("112", "직권 취소(상담사)"),
            Map.entry("113", "자동 취소(결제부족)"),
            Map.entry("114", "자동 취소(결품취소)"),
            Map.entry("115", "자동 취소(선물미수령)"),
            Map.entry("116", "자동 취소(스마트픽미수령)"),
            Map.entry("117", "자동 취소(취소요청 정책 접수)"),
            Map.entry("120", "선물거절 (선물 수락 전 취소)"),
            Map.entry("124", "가격/쿠폰 오류(롯데ON)"),
            Map.entry("130", "직권 취소(장기미발송)"),
            Map.entry("132", "가격오등록(판매자취소)"),
            Map.entry("133", "리셀러주문(판매자취소)"),
            Map.entry("134", "택배 지원 불가(판매자취소)"),
            Map.entry("135", "판매자 취소(고객변심)"),
            Map.entry("137", "택배지원불가"),
            Map.entry("139", "상품이 품절됨(상담사)"));

    private LotteonCancelReasons() {
    }

    /**
     * 「name — text」; the text blank → the name only. A code not in the table → 「사유 코드 {code}」 · no code →
     * 「사유 코드 없음」.
     */
    public static String label(String code, String text) {
        String name;
        if (code == null || code.isBlank()) {
            name = "사유 코드 없음";
        } else {
            name = NAMES.getOrDefault(code.trim(), "사유 코드 " + code.trim());
        }
        if (text == null || text.isBlank()) {
            return name;
        }
        return name + " — " + text.trim();
    }
}
