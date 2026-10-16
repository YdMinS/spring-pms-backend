package com.pms.service.elevenst;

import java.util.Map;

/**
 * 11st cancel reason codes ({@code ordCnRsnCd}, 1639 original table) → the label the order detail shows
 * (FEATURE_2610_17 / D41). Codes 06~10 mean different things for a buyer and a seller request — the label follows
 * who filed it ({@code ordCnMnbdCd} 02 = seller, anything else = buyer).
 */
public final class ElevenstCancelReasons {

    private static final String REQUESTED_BY_SELLER = "02";

    private static final Map<String, String> SINGLE = Map.ofEntries(
            Map.entry("00", "무통장 미입금 취소"),
            Map.entry("04", "판매자의 배송 처리가 늦음"),
            Map.entry("11", "구매 의사 없어짐"),
            Map.entry("12", "기타(구매자 책임사유)"),
            Map.entry("13", "기타(판매자 책임사유)"),
            Map.entry("99", "기타"),
            Map.entry("14", "구매의사 없어짐"),
            Map.entry("15", "색상/사이즈/주문정보 변경"),
            Map.entry("16", "다른 상품 잘못 주문"),
            Map.entry("17", "(판매자)배송지연으로 취소"),
            Map.entry("18", "(판매자)상품품절, 재고없음"));

    private static final Map<String, String> BUYER = Map.of(
            "06", "상품 정보가 잘못됨",
            "07", "동일 상품 재주문",
            "08", "품절/재고없음",
            "09", "11번가 내 다른 상품으로 재주문",
            "10", "타사이트 상품 주문");

    private static final Map<String, String> SELLER = Map.of(
            "06", "배송 지연 예상",
            "07", "상품/가격 정보 잘못 입력",
            "08", "상품 품절(전체옵션)",
            "09", "옵션 품절(해당옵션)",
            "10", "고객변심");

    private ElevenstCancelReasons() {
    }

    /**
     * D41: 「사유 이름 — 사유 글」; the name alone when there is no text; 「사유 코드 {코드}」 for a code not in
     * the table.
     */
    public static String label(String code, String requestedBy, String text) {
        String name = name(code, requestedBy);
        return (text == null || text.isBlank()) ? name : name + " — " + text.trim();
    }

    private static String name(String code, String requestedBy) {
        String key = code == null ? "" : code.trim();
        if (SINGLE.containsKey(key)) {
            return SINGLE.get(key);
        }
        Map<String, String> dual = REQUESTED_BY_SELLER.equals(requestedBy) ? SELLER : BUYER;
        return dual.getOrDefault(key, "사유 코드 " + key);
    }
}
