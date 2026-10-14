package com.pms.service.listing.shipping;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The 11st shipping keys (FEATURE_2610_13 / D91 · D104 · D106) — the single vocabulary shared by the channel
 * default ({@code ElevenstShippingConfig.settings}), the master shipping override and the 11st listing shipping
 * override. Every key starts with {@link #PREFIX} so 11st values sit next to the Coupang keys in the master's one
 * override map without colliding (D104). The part after the prefix is the 11st 1003 field name.
 *
 * <p>Values are strings. Codes (D91): area 01·02 · method 01·03·04·05 · fee type 01·02·03·04·05·07·08 · payment
 * 01·02·03 · cash-on-delivery note 01·02·03·04·06 · bundle Y·N · return fee rule 01·02 (blank = 11st decides).
 * 「04 수량별 차등」 tiers are stored exactly as 11st takes them: {@code ^}-joined lists (D106).</p>
 *
 * <p>⚠️ Outbound / return address numbers ({@link #PLACE_KEYS}) are account-specific → never on the master
 * (same rule as the Coupang place keys).</p>
 */
public final class ElevenstShippingKeys {

    public static final String PREFIX = "elevenst.";

    public static final String DLV_CN_AREA_CD = "elevenst.dlvCnAreaCd";
    public static final String DLV_WY_CD = "elevenst.dlvWyCd";
    public static final String DLV_ETPRS_CD = "elevenst.dlvEtprsCd";
    public static final String DLV_CST_INST_BASI_CD = "elevenst.dlvCstInstBasiCd";
    public static final String DLV_CST1 = "elevenst.dlvCst1";
    public static final String PRD_FR_DLV_BASI_AMT = "elevenst.prdFrDlvBasiAmt";
    public static final String DLV_CNT1 = "elevenst.dlvCnt1";
    public static final String DLV_CNT2 = "elevenst.dlvCnt2";
    public static final String DLV_CST3 = "elevenst.dlvCst3";
    public static final String DLV_CST4 = "elevenst.dlvCst4";
    public static final String DLV_CST_PAY_TYP_CD = "elevenst.dlvCstPayTypCd";
    public static final String DLV_CST_INFO_CD = "elevenst.dlvCstInfoCd";
    public static final String BNDL_DLV_CN_YN = "elevenst.bndlDlvCnYn";
    public static final String JEJU_DLV_CST = "elevenst.jejuDlvCst";
    public static final String ISLAND_DLV_CST = "elevenst.islandDlvCst";
    public static final String RTNGD_DLV_CST = "elevenst.rtngdDlvCst";
    public static final String EXCH_DLV_CST = "elevenst.exchDlvCst";
    public static final String RTNGD_DLV_CD = "elevenst.rtngdDlvCd";
    public static final String ADDR_SEQ_OUT = "elevenst.addrSeqOut";
    public static final String ADDR_SEQ_IN = "elevenst.addrSeqIn";

    /** D106: at most 10 quantity tiers. */
    public static final int MAX_TIERS = 10;

    /** Account-specific address numbers — channel default + listing only. */
    public static final Set<String> PLACE_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            ADDR_SEQ_OUT, ADDR_SEQ_IN)));

    /** Every 11st key = the channel default / 11st listing whitelist, in the order of the 11st shipping fields. */
    public static final Set<String> ALL_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            ADDR_SEQ_OUT, ADDR_SEQ_IN, DLV_CN_AREA_CD, DLV_WY_CD, DLV_ETPRS_CD, BNDL_DLV_CN_YN,
            DLV_CST_INST_BASI_CD, DLV_CST_PAY_TYP_CD, DLV_CST1, PRD_FR_DLV_BASI_AMT, DLV_CST4, DLV_CST_INFO_CD,
            DLV_CNT1, DLV_CNT2, DLV_CST3, JEJU_DLV_CST, ISLAND_DLV_CST, RTNGD_DLV_CST, EXCH_DLV_CST, RTNGD_DLV_CD)));

    /** Master whitelist = every key except the address numbers. */
    public static final Set<String> MASTER_KEYS;

    static {
        Set<String> master = new LinkedHashSet<>(ALL_KEYS);
        master.removeAll(PLACE_KEYS);
        MASTER_KEYS = Collections.unmodifiableSet(master);
    }

    /** Screen labels, used in the "missing field" message (register guard). */
    public static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(DLV_CN_AREA_CD, "배송 가능 지역");
        labels.put(DLV_WY_CD, "배송 방법");
        labels.put(DLV_ETPRS_CD, "택배사");
        labels.put(DLV_CST_INST_BASI_CD, "배송비 종류");
        labels.put(DLV_CST1, "배송비");
        labels.put(PRD_FR_DLV_BASI_AMT, "무료배송 기준 금액");
        labels.put(DLV_CNT1, "수량별 배송비 구간");
        labels.put(DLV_CNT2, "수량별 배송비 구간");
        labels.put(DLV_CST3, "수량별 배송비");
        labels.put(DLV_CST4, "1개당 배송비");
        labels.put(DLV_CST_PAY_TYP_CD, "결제 방법");
        labels.put(DLV_CST_INFO_CD, "착불 안내");
        labels.put(BNDL_DLV_CN_YN, "묶음배송");
        labels.put(JEJU_DLV_CST, "제주 추가 배송비");
        labels.put(ISLAND_DLV_CST, "도서산간 추가 배송비");
        labels.put(RTNGD_DLV_CST, "반품 배송비");
        labels.put(EXCH_DLV_CST, "교환 배송비");
        labels.put(RTNGD_DLV_CD, "반품비 부과 방법");
        labels.put(ADDR_SEQ_OUT, "출고지");
        labels.put(ADDR_SEQ_IN, "반품/교환지");
        LABELS = Collections.unmodifiableMap(labels);
    }

    private ElevenstShippingKeys() {
    }

    public static boolean isElevenstKey(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    /** Keep only the 11st keys of {@code allowed}; {@code null} when nothing is left (= no override). */
    public static Map<String, String> filter(Map<String, String> raw, Set<String> allowed) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (allowed.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered.isEmpty() ? null : filtered;
    }

    /**
     * D106: the 「04 수량별 차등」 tiers must chain — first tier starts at 1, each next tier starts at the previous
     * end + 1, the last tier has no end, at most {@link #MAX_TIERS}. Validated wherever the tiers are saved
     * (channel default · master · listing). All three keys blank = no tiers set → nothing to check.
     *
     * @throws IllegalArgumentException (→ 400) when the tiers do not chain
     */
    public static void validateTiers(Map<String, String> settings) {
        if (settings == null) {
            return;
        }
        String starts = settings.get(DLV_CNT1);
        String ends = settings.get(DLV_CNT2);
        String fees = settings.get(DLV_CST3);
        if (isBlank(starts) && isBlank(ends) && isBlank(fees)) {
            return;
        }
        List<BigInteger> startList = numbers(starts);
        List<BigInteger> endList = numbers(ends);
        List<BigInteger> feeList = numbers(fees);
        if (startList.size() > MAX_TIERS) {
            throw new IllegalArgumentException("수량별 배송비는 10구간까지 넣을 수 있습니다.");
        }
        boolean chained = !startList.isEmpty()
                && feeList.size() == startList.size()
                && endList.size() == startList.size() - 1
                && BigInteger.ONE.equals(startList.get(0));
        for (int i = 0; chained && i < endList.size(); i++) {
            chained = endList.get(i).compareTo(startList.get(i)) >= 0
                    && startList.get(i + 1).equals(endList.get(i).add(BigInteger.ONE));
        }
        if (!chained) {
            throw new IllegalArgumentException(
                    "수량별 배송비 구간이 이어지지 않습니다. 첫 구간은 1개부터, 다음 구간은 앞 구간 끝 수량 + 1 부터 시작해야 합니다.");
        }
    }

    /** {@code ^}-joined non-negative integers; anything else (or a blank part) breaks the chain. */
    private static List<BigInteger> numbers(String joined) {
        List<BigInteger> out = new ArrayList<>();
        if (isBlank(joined)) {
            return out;
        }
        for (String part : joined.split("\\^", -1)) {
            String trimmed = part.trim();
            if (!trimmed.matches("\\d+")) {
                throw new IllegalArgumentException(
                        "수량별 배송비 구간이 이어지지 않습니다. 첫 구간은 1개부터, 다음 구간은 앞 구간 끝 수량 + 1 부터 시작해야 합니다.");
            }
            out.add(new BigInteger(trimmed));
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
