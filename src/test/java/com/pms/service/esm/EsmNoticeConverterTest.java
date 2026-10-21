package com.pms.service.esm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FEATURE_2610_26 / D16 · D17: the E1~E296 table rules. */
class EsmNoticeConverterTest {

    @Test
    void table_hasEveryRowOfTheDiscussion() {
        long rows = EsmNoticeMap.GROUPS.stream().flatMap(g -> g.rows().stream())
                .filter(r -> r.rule() != EsmNoticeMap.Rule.ESM_ONLY).count();
        long esmOnly = EsmNoticeMap.GROUPS.stream().flatMap(g -> g.rows().stream())
                .filter(r -> r.rule() == EsmNoticeMap.Rule.ESM_ONLY).count();
        assertThat(rows).isEqualTo(296);
        assertThat(esmOnly).isEqualTo(27);
    }

    @Test
    void valueSentAsIs_blankAsReference_joinedWithSlash() {
        EsmNoticeConverter.Notification n = EsmNoticeConverter.convert("사무용기기(컴퓨터/노트북/프린터 등)",
                Map.of("품명 및 모델명", " 노트북 ", "크기", "30cm", "무게", "1kg"));

        assertThat(n.groupNo()).isEqualTo("10");
        assertThat(n.lines()).filteredOn(l -> l.code().equals("10-1")).singleElement()
                .satisfies(l -> {
                    assertThat(l.value()).isEqualTo("노트북");
                    assertThat(l.extraMark()).isFalse();
                });
        assertThat(n.lines()).filteredOn(l -> l.code().equals("10-7")).singleElement()
                .satisfies(l -> assertThat(l.value()).isEqualTo("30cm / 1kg"));
        assertThat(n.lines()).filteredOn(l -> l.code().equals("10-2")).singleElement()
                .satisfies(l -> {
                    assertThat(l.value()).isEqualTo("상품 상세페이지 참조");
                    assertThat(l.extraMark()).isTrue();
                });
    }

    @Test
    void esmOnlyItem_readsItsOwnKey() {
        EsmNoticeConverter.Notification n = EsmNoticeConverter.convert("의류", Map.of("ESM:1-10", "3일"));

        assertThat(n.lines()).filteredOn(l -> l.code().equals("1-10")).singleElement()
                .satisfies(l -> {
                    assertThat(l.value()).isEqualTo("3일");
                    assertThat(l.ownKey()).isEqualTo("ESM:1-10");
                });
    }

    @Test
    void unknownGroup_sendsTheSixEtcItemsAsReference() {
        EsmNoticeConverter.Notification n = EsmNoticeConverter.convert(null, Map.of("ESM:35-6", "3일"));

        assertThat(n.groupNo()).isEqualTo("35");
        assertThat(n.lines()).hasSize(6).allSatisfy(l -> {
            assertThat(l.value()).isEqualTo("상품 상세페이지 참조");
            assertThat(l.extraMark()).isTrue();
        });
    }
}
