package com.pms.service.lotteon;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FEATURE_2610_24 / D13 · D58 · D62 · D63 · D64: LotteON notice both ways and the master fill. */
class LotteonNoticeConverterTest {

    private static Map<String, String> map(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String value(LotteonNoticeConverter.Notification n, String code) {
        return n.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow().value();
    }

    @Test
    void tables_have296RowsAnd12OwnGroupsOf88Items() {
        assertThat(LotteonNoticeMap.GROUPS).hasSize(29);
        assertThat(LotteonNoticeMap.GROUPS.stream().mapToInt(g -> g.rows().size()).sum()).isEqualTo(296);
        assertThat(LotteonNoticeMap.OWN_GROUPS).hasSize(12);
        assertThat(LotteonNoticeMap.OWN_GROUPS.stream().mapToInt(g -> g.items().size()).sum()).isEqualTo(88);
    }

    @Test
    void convert_tableGroup_sameJoinSplitNotSent_blankIsRefText() {
        LotteonNoticeConverter.Notification office = LotteonNoticeConverter.convert("사무용기기(컴퓨터/노트북/프린터 등)",
                map("크기", "10cm", "무게", "200g", "제조국", " 한국 "));
        assertThat(office.itemGroupCode()).isEqualTo("10");
        assertThat(value(office, "0300")).isEqualTo("10cm / 200g");
        assertThat(value(office, "0060")).isEqualTo("한국");
        assertThat(value(office, "0310")).isEqualTo("상품 상세페이지 참조");

        LotteonNoticeConverter.Notification jewel = LotteonNoticeConverter.convert("귀금속/보석/시계류",
                map("주요사양", "방수"));
        assertThat(value(jewel, "1800")).isEqualTo("방수");
        assertThat(value(jewel, "1850")).isEqualTo("방수");

        LotteonNoticeConverter.Notification phone = LotteonNoticeConverter.convert("휴대형 통신기기 (휴대폰 / 태블릿 등)",
                map("이동통신 가입조건", "약정"));
        assertThat(phone.usedCoupangItems()).doesNotContain("이동통신 가입조건");
    }

    @Test
    void convert_noTableGroup_ownGroupKey_sendsThatGroupFromLotteonKeys() {
        LotteonNoticeConverter.Notification tv = LotteonNoticeConverter.convert(null,
                map("LOTTEON:품목", "07", "LOTTEON:07:0240", "55인치 4K"));

        assertThat(tv.itemGroupCode()).isEqualTo("07");
        assertThat(tv.lines()).hasSize(12);
        assertThat(value(tv, "0240")).isEqualTo("55인치 4K");
        assertThat(value(tv, "0210")).isEqualTo("상품 상세페이지 참조");
        assertThat(tv.lines().get(0).ownKey()).isEqualTo("LOTTEON:07:0210");
    }

    @Test
    void convert_nothing_isEtc38WithRefText() {
        LotteonNoticeConverter.Notification etc = LotteonNoticeConverter.convert("", Map.of());

        assertThat(etc.itemGroupCode()).isEqualTo("38");
        assertThat(etc.lines()).extracting(LotteonNoticeConverter.Line::code)
                .containsExactly("0210", "1400", "1420", "0070", "1440");
        assertThat(etc.lines()).extracting(LotteonNoticeConverter.Line::value).containsOnly("상품 상세페이지 참조");
    }

    @Test
    void fromLotteon_tableGroup_splitsJoinRows_mergesSplitRows_38IsEtcGoods() {
        LotteonNoticeConverter.Imported office = LotteonNoticeConverter.fromLotteon("10",
                map("0300", "10cm / 200g", "0060", "한국"));
        assertThat(office.coupangGroup()).isEqualTo("사무용기기(컴퓨터/노트북/프린터 등)");
        assertThat(office.notices()).containsEntry("크기", "10cm").containsEntry("무게", "200g")
                .containsEntry("제조국", "한국");

        LotteonNoticeConverter.Imported oneText = LotteonNoticeConverter.fromLotteon("10",
                map("0300", "가로10 세로20cm, 200g"));
        assertThat(oneText.notices()).containsEntry("크기", "가로10 세로20cm, 200g").doesNotContainKey("무게");

        LotteonNoticeConverter.Imported jewel = LotteonNoticeConverter.fromLotteon("19",
                map("1800", "상품 상세페이지 참조", "1850", "상품 상세페이지 참조"));
        assertThat(jewel.notices()).containsEntry("주요사양", "상품 상세페이지 참조");

        assertThat(LotteonNoticeConverter.fromLotteon("38", map("0210", "모델")).coupangGroup())
                .isEqualTo("기타 재화");
    }

    @Test
    void fromLotteon_ownGroup_noCoupangGroup_valuesUnderLotteonKeys() {
        LotteonNoticeConverter.Imported tv = LotteonNoticeConverter.fromLotteon("07",
                map("0240", "55인치 4K", "0210", " "));

        assertThat(tv.coupangGroup()).isNull();
        assertThat(tv.notices()).containsExactly(Map.entry("LOTTEON:품목", "07"),
                Map.entry("LOTTEON:07:0240", "55인치 4K"));
    }

    @Test
    void fillMissing_onlyEmptyKeys_sameGroupOnly_ownKeysOfTheMastersOwnGroup() {
        LotteonNoticeConverter.Imported office = new LotteonNoticeConverter.Imported(
                "사무용기기(컴퓨터/노트북/프린터 등)", map("크기", "10cm", "제조국", "중국(OEM)"));

        LotteonNoticeConverter.Filled empty = LotteonNoticeConverter.fillMissing(null, null, office);
        assertThat(empty.changed()).isTrue();
        assertThat(empty.group()).isEqualTo("사무용기기(컴퓨터/노트북/프린터 등)");
        assertThat(empty.notices()).containsEntry("크기", "10cm").containsEntry("제조국", "중국(OEM)");

        LotteonNoticeConverter.Filled kept = LotteonNoticeConverter.fillMissing("사무용기기(컴퓨터/노트북/프린터 등)",
                map("제조국", "한국", "크기", " "), office);
        assertThat(kept.notices()).containsEntry("제조국", "한국").containsEntry("크기", "10cm");

        LotteonNoticeConverter.Filled other = LotteonNoticeConverter.fillMissing("기타 재화(유통기간)",
                map(), new LotteonNoticeConverter.Imported("기타 재화", map("품명 및 모델명", "모델")));
        assertThat(other.changed()).isFalse();
        assertThat(other.group()).isEqualTo("기타 재화(유통기간)");

        LotteonNoticeConverter.Imported tv = new LotteonNoticeConverter.Imported(null,
                map("LOTTEON:품목", "07", "LOTTEON:07:0240", "55인치"));
        LotteonNoticeConverter.Filled own = LotteonNoticeConverter.fillMissing(null, map("색상", "검정"), tv);
        assertThat(own.group()).isNull();
        assertThat(own.notices()).containsEntry("LOTTEON:품목", "07").containsEntry("LOTTEON:07:0240", "55인치")
                .containsEntry("색상", "검정");

        LotteonNoticeConverter.Filled otherOwn = LotteonNoticeConverter.fillMissing(null,
                map("LOTTEON:품목", "29"), tv);
        assertThat(otherOwn.changed()).isFalse();
    }
}
