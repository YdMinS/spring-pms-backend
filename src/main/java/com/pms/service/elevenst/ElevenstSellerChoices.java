package com.pms.service.elevenst;

import com.pms.service.claim.ActionChoice;

import java.util.List;

/**
 * Code lists of the 11st seller actions (FEATURE_2610_17 / D61 · D62 · D63 · D68). The labels are the 11st guide's
 * own names, word for word — the screens show them as given and never keep a code→label map of their own.
 *
 * <p>⚠️ The server checks every incoming code against these lists before calling 11st (a code outside the list
 * is a 400, not a 11st round trip).
 */
public final class ElevenstSellerChoices {

    /** 1638 {@code ordCnRsnCd} — sale refusal reasons (D61). {@value #CUSTOMER_CHANGE} keeps the seller score. */
    public static final List<ActionChoice> SELLER_CANCEL_REASONS = List.of(
            new ActionChoice("06", "배송 지연 예상"),
            new ActionChoice("07", "상품/가격 정보 잘못 입력"),
            new ActionChoice("08", "상품 품절(전체옵션)"),
            new ActionChoice("09", "옵션 품절(해당옵션)"),
            new ActionChoice("10", "고객변심"),
            new ActionChoice("99", "기타"));

    /** 1638 reason that costs no seller credit score (D61). */
    public static final String CUSTOMER_CHANGE = "10";

    /** 1745 {@code ordCnRefsRsnCd} — why a seller-fault cancel request is refused (D62). */
    public static final List<ActionChoice> FAULT_REJECT_REASONS = List.of(
            new ActionChoice("01", "취소 책임사유 입력 오류"),
            new ActionChoice("02", "상품 발송처리 완료"));

    /** 6728 {@code delaySendRsnCd} — shipping-delay reasons (D63). */
    public static final List<ActionChoice> DELAY_REASONS = List.of(
            new ActionChoice("01", "단기 재고 부족"),
            new ActionChoice("02", "주문폭주로 인한 작업지연"),
            new ActionChoice("03", "주문제작 시간이 필요"),
            new ActionChoice("04", "고객 요청"),
            new ActionChoice("05", "기타"));

    /** 6734 {@code sellerDtlsRsn} — non-delivery handling reasons (D68). {@value #RESEND} needs carrier + invoice. */
    public static final List<ActionChoice> NON_DELIVERY_REASONS = List.of(
            new ActionChoice("101", "구매자와 연락하여 협의하였습니다."),
            new ActionChoice("102", "재발송하였습니다."),
            new ActionChoice("103", "반품처리 하겠습니다."),
            new ActionChoice("104", "택배를 수령하시는 곳에서 다시 확인해 주세요."));

    /** 6734 reason that sends the re-shipping carrier and invoice (D68). */
    public static final String RESEND = "102";

    private ElevenstSellerChoices() {
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
