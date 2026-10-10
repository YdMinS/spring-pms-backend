package com.pms.service.category;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ElevenstFeeTableParser} (FEATURE_2610_10 / D27 ①~⑤): table chosen by header, rowspan copied down
 * (broken value → leading digits), exception groups, non-{@code N%} fee kept as null, charset fallback, and the
 * 400 when no fee table exists.
 */
class ElevenstFeeTableParserTest {

    private final ElevenstFeeTableParser parser = new ElevenstFeeTableParser();

    @Test
    void parse_savedPage_readsFeeTableOnlyAndCopiesRowspanDown() {
        List<ElevenstFeeRow> rows = parser.parse(ElevenstFixtures.feeHtml());

        assertThat(rows).hasSize(9);
        assertThat(rows.get(0).group()).isEqualTo("가전/디지털");
        assertThat(rows.get(0).category()).isEqualTo("스마트기기");
        assertThat(rows.get(0).feePercent()).isEqualTo(9);
        // rows 2~5 share the group cell; rows 4~5 share the fee cell and the broken-rowspan exclusion cell
        assertThat(rows.get(4)).isEqualTo(new ElevenstFeeRow("생활/건강", "청소용품", "13%", 13, List.of()));
        assertThat(rows.get(5).group()).isEqualTo("브랜드 패션");
        assertThat(rows.get(7).group()).isEqualTo("해외직구");
    }

    @Test
    void parse_exclusionColumn_readsGroupsNamesAndIgnoresTextWithoutPercent() {
        List<ElevenstFeeRow> rows = parser.parse(ElevenstFixtures.feeHtml());

        assertThat(rows.get(1).exclusions()).containsExactly(
                new ElevenstFeeRow.Excluded("기타 주방용품", 7),
                new ElevenstFeeRow.Excluded("프라이팬", 7));
        assertThat(rows.get(2).exclusions()).containsExactly(
                new ElevenstFeeRow.Excluded("금연용품", 12),
                new ElevenstFeeRow.Excluded("성인용품", 12),
                new ElevenstFeeRow.Excluded("수지침", 11));
        assertThat(rows.get(3).exclusions()).isEmpty();   // 「전체 하위카테고리 동일」
        assertThat(rows.get(6).exclusions()).isEmpty();   // 「1% 혹은 정액」
    }

    @Test
    void parse_feeNotPercent_keepsRowWithNullPercent() {
        ElevenstFeeRow row = parser.parse(ElevenstFixtures.feeHtml()).get(8);

        assertThat(row.category()).isEqualTo("주방잡화");
        assertThat(row.feeText()).isEqualTo("정액");
        assertThat(row.feePercent()).isNull();
    }

    @Test
    void parse_noMetaCharset_readsAsEucKr() {
        String withoutMeta = new String(ElevenstFixtures.feeHtml(), ElevenstFixtures.EUC_KR)
                .replace("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=EUC-KR\">", "");

        assertThat(ElevenstFeeTableParser.charsetOf(withoutMeta.getBytes(ElevenstFixtures.EUC_KR)))
                .isEqualTo("EUC-KR");
        assertThat(parser.parse(withoutMeta.getBytes(ElevenstFixtures.EUC_KR)).get(0).group())
                .isEqualTo("가전/디지털");
    }

    @Test
    void parse_noFeeTable_throws400() {
        byte[] decoyOnly = "<html><body><table><tr><th>구분</th><th>판매가</th></tr></table></body></html>"
                .getBytes(ElevenstFixtures.EUC_KR);

        assertThatThrownBy(() -> parser.parse(decoyOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("11번가 수수료 안내 표를 찾지 못했습니다.");
    }
}
