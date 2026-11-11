package com.pms.service.lotteon;

import com.pms.service.claim.ActionChoice;

import java.util.List;

/**
 * Code lists of the LotteON seller actions (FEATURE_2611_23 / D24 · D25 · D26 · D28 · D58). The labels are the
 * LotteON guide's own names, word for word — the screens show them as given ({@code GET /api/orders/lotteon-choices})
 * and never keep a code→label map of their own.
 *
 * <p>⚠️ The server checks every incoming code against these lists before calling LotteON (a code outside the list
 * is a 400, not a LotteON round trip).
 */
public final class LotteonSellerChoices {

    /** [225] {@code slrRsnCd} — seller direct cancel reasons (D25). */
    public static final List<ActionChoice> SELLER_CANCEL_REASONS = List.of(
            new ActionChoice("111", "판매자 취소(품절)"),
            new ActionChoice("132", "가격오등록"),
            new ActionChoice("133", "리셀러주문"),
            new ActionChoice("137", "택배지원불가"),
            new ActionChoice("135", "판매자 취소(고객변심)"));

    /** [59] {@code slrRsnCd} — cancel request rejection reasons (D24). */
    public static final List<ActionChoice> CANCEL_REJECT_REASONS = List.of(
            new ActionChoice("901", "이미 상품을 발송함"),
            new ActionChoice("902", "고객과 협의 후 취소거부"),
            new ActionChoice("903", "취소가 어려운 상품(주문제작상품 등)"));

    /** [138] {@code sndDlRsnCd} — shipping appointment reasons (D26). {@value #MAKING_DELAY} needs a detail. */
    public static final List<ActionChoice> APPOINTMENT_REASONS = List.of(
            new ActionChoice("STK_SHTG", "재고부족"),
            new ActionChoice("OVS_DV", "해외배송"),
            new ActionChoice("CUST_REQ", "고객요청"),
            new ActionChoice("PD_DL", "제작지연"));

    /** [138] {@code sndDlRsnCnts} — the details of {@value #MAKING_DELAY} (D26). */
    public static final List<ActionChoice> APPOINTMENT_DETAIL_REASONS = List.of(
            new ActionChoice("INDV_CSTM", "개별 맞춤 신청에 따른 제작 일정 추가"),
            new ActionChoice("ORD_INC_DL", "주문량 증가에 따른 상품 제작 지연"));

    /** [141] return / exchange hold reasons (D28 ③). */
    public static final List<ActionChoice> HOLD_REASONS = List.of(
            new ActionChoice("01", "상품 미회수"),
            new ActionChoice("02", "반품비 입금 필요"),
            new ActionChoice("04", "상품 훼손"),
            new ActionChoice("07", "상품 검수 중"),
            new ActionChoice("08", "상품 오입고"));

    /** [138] making delay — the one reason that needs {@link #APPOINTMENT_DETAIL_REASONS} (D26). */
    public static final String MAKING_DELAY = "PD_DL";

    private LotteonSellerChoices() {
    }

    /** The label of {@code code} in {@code choices}; null when the code is not in the list. */
    public static String label(List<ActionChoice> choices, String code) {
        return choices.stream().filter(c -> c.code().equals(code)).map(ActionChoice::label).findFirst()
                .orElse(null);
    }

    /** The label of {@code code} in {@code choices}, or a 400 with {@code message} when the code is not listed. */
    public static String requireLabel(List<ActionChoice> choices, String code, String message) {
        String label = (code == null) ? null : label(choices, code.trim());
        if (label == null) {
            throw new IllegalArgumentException(message);
        }
        return label;
    }
}
