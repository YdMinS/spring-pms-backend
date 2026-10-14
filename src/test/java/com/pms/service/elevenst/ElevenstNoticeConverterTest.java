package com.pms.service.elevenst;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FEATURE_2610_13 / D89 · D100 · D101 · D102. */
class ElevenstNoticeConverterTest {

    @Test
    void mapCoversTwentyNineGroupsAndTwoHundredNinetyFiveRows() {
        assertThat(ElevenstNoticeMap.GROUPS).hasSize(29);
        assertThat(ElevenstNoticeMap.GROUPS.stream().mapToInt(g -> g.rows().size()).sum()).isEqualTo(295);
    }

    @Test
    void join_joinsInCoupangOrder_andCollapsesEqualValues() {
        Map<String, String> notices = new LinkedHashMap<>();
        notices.put("크기", "10x20");
        notices.put("무게", "1kg");
        ElevenstNoticeConverter.Notification joined =
                ElevenstNoticeConverter.convert("사무용기기(컴퓨터/노트북/프린터 등)", notices);
        assertThat(joined.typeCode()).isEqualTo("891020");
        assertThat(line(joined, "23760172").value()).isEqualTo("10x20 / 1kg");
        assertThat(joined.usedCoupangItems()).containsExactly("크기", "무게");

        ElevenstNoticeConverter.Notification same = ElevenstNoticeConverter.convert("사무용기기(컴퓨터/노트북/프린터 등)",
                Map.of("크기", ElevenstNoticeConverter.REF_TEXT, "무게", ElevenstNoticeConverter.REF_TEXT));
        assertThat(line(same, "23760172").value()).isEqualTo(ElevenstNoticeConverter.REF_TEXT);
    }

    @Test
    void elevenstOnly_readsOwnKey_blankOrTooLongBecomesReference() {
        ElevenstNoticeConverter.Notification result = ElevenstNoticeConverter.convert("농수축산물", Map.of(
                "ELEVENST:471785504", "해당 없음",
                "품목 또는 명칭", "가".repeat(51)));
        assertThat(line(result, "471785504").value()).isEqualTo("해당 없음");
        assertThat(line(result, "471785504").ownKey()).isEqualTo("ELEVENST:471785504");
        assertThat(line(result, "471785749").value()).isEqualTo(ElevenstNoticeConverter.REF_TEXT);
        assertThat(line(result, "176308791").value()).isEqualTo(ElevenstNoticeConverter.REF_TEXT);
    }

    @Test
    void splitCopiesOneValue_notSentIsDropped() {
        ElevenstNoticeConverter.Notification book =
                ElevenstNoticeConverter.convert("서적", Map.of("저자, 출판사", "홍길동 / 출판사"));
        assertThat(line(book, "11901").value()).isEqualTo("홍길동 / 출판사");
        assertThat(line(book, "11924").value()).isEqualTo("홍길동 / 출판사");

        ElevenstNoticeConverter.Notification etc = ElevenstNoticeConverter.convert("기타 재화(유통기간)", Map.of());
        assertThat(etc.lines()).extracting(ElevenstNoticeConverter.Line::code)
                .doesNotContainNull().hasSize(5);
    }

    @Test
    void unknownOrBlankGroup_sendsEtcWithFiveReferenceItems() {
        for (String group : new String[]{null, "", "없는 품목군"}) {
            ElevenstNoticeConverter.Notification result = ElevenstNoticeConverter.convert(group, Map.of("색상", "빨강"));
            assertThat(result.typeCode()).isEqualTo("891045");
            assertThat(result.lines()).extracting(ElevenstNoticeConverter.Line::code)
                    .containsExactly("11800", "11905", "23760413", "23759100", "23756033");
            assertThat(result.lines()).allMatch(l -> ElevenstNoticeConverter.REF_TEXT.equals(l.value()));
            assertThat(result.usedCoupangItems()).isEqualTo(List.of());
        }
    }

    private static ElevenstNoticeConverter.Line line(ElevenstNoticeConverter.Notification n, String code) {
        return n.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow();
    }
}
