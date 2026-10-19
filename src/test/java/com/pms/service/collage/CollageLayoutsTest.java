package com.pms.service.collage;

import com.pms.domain.CollageCell;
import com.pms.domain.CollageLayout;
import com.pms.domain.CollageShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure collage rules (FEATURE_2610_21): shapes per count (D30), the read-time adjustment (D11 · D43), save
 * validation and the cell geometry (D44).
 */
class CollageLayoutsTest {

    private static CollageLayout leftBig(List<CollageCell> cells) {
        return new CollageLayout(CollageShape.LEFT_BIG, 0.6, List.of(List.of(0.3, 0.7)), 0.02, "#000000",
                Boolean.FALSE, cells);
    }

    @Test
    void shapesFor_countRules() {
        assertThat(CollageLayouts.shapesFor(1)).isEmpty();
        assertThat(CollageLayouts.shapesFor(2)).containsExactly(CollageShape.ROW, CollageShape.COLUMN);
        assertThat(CollageLayouts.shapesFor(3)).hasSize(4).doesNotContain(CollageShape.TWO_ROWS);
        assertThat(CollageLayouts.shapesFor(5)).hasSize(5);
    }

    /** D11 same-count case · D43: the vacated cell takes the new product; everything else is kept. */
    @Test
    void adjust_sameCount_fillsVacatedCellAndKeepsRatios() {
        CollageLayout saved = leftBig(List.of(
                new CollageCell(1L, 2.0, 0.2, 0.3), new CollageCell(2L, 1.5, 0.4, 0.4),
                new CollageCell(3L, 1.2, 0.6, 0.6)));

        CollageLayout adjusted = CollageLayouts.adjust(saved, List.of(1L, 3L, 4L));

        assertThat(adjusted.cells()).containsExactly(
                new CollageCell(1L, 2.0, 0.2, 0.3), new CollageCell(4L, 1.0, 0.5, 0.5),
                new CollageCell(3L, 1.2, 0.6, 0.6));
        assertThat(adjusted.shape()).isEqualTo(CollageShape.LEFT_BIG);
        assertThat(adjusted.main()).isEqualTo(0.6);
        assertThat(adjusted.lines()).isEqualTo(List.of(List.of(0.3, 0.7)));
        assertThat(adjusted.gapColor()).isEqualTo("#000000");
    }

    /** D11 count-change case · D43: kept cells first, new ones after, ratios reset, unsupported shape → ROW. */
    @Test
    void adjust_countChanged_appendsNewResetsRatiosAndFallsBackToRow() {
        CollageLayout saved = leftBig(List.of(
                new CollageCell(1L, 2.0, 0.2, 0.3), new CollageCell(2L, 1.5, 0.4, 0.4),
                new CollageCell(3L, 1.2, 0.6, 0.6)));

        CollageLayout adjusted = CollageLayouts.adjust(saved, List.of(3L, 5L));

        assertThat(adjusted.shape()).isEqualTo(CollageShape.ROW);
        assertThat(adjusted.main()).isNull();
        assertThat(adjusted.lines()).isEqualTo(List.of(List.of(0.5, 0.5)));
        assertThat(adjusted.cells()).containsExactly(
                new CollageCell(3L, 1.2, 0.6, 0.6), new CollageCell(5L, 1.0, 0.5, 0.5));
        assertThat(adjusted.gap()).isEqualTo(0.02);
        assertThat(adjusted.useTemplate()).isFalse();
    }

    @Test
    void validate_rejectsCellsThatDoNotMatchTheComponents() {
        CollageLayout layout = CollageLayouts.initial(List.of(1L, 2L));

        assertThatThrownBy(() -> CollageLayouts.validate(layout, List.of(1L, 3L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("칸의 물품이 구성품과 다릅니다.");
        assertThatThrownBy(() -> CollageLayouts.validate(layout, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("구성품이 2개 이상인 마스터만 콜라주를 만들 수 있습니다.");
    }

    @Test
    void validate_rejectsALineCellBelowTenPercent() {
        CollageLayout layout = new CollageLayout(CollageShape.ROW, null, List.of(List.of(0.05, 0.95)), 0.0,
                "#FFFFFF", Boolean.TRUE, List.of(CollageLayouts.initialCell(1L), CollageLayouts.initialCell(2L)));

        assertThatThrownBy(() -> CollageLayouts.validate(layout, List.of(1L, 2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("칸 크기 비율이 올바르지 않습니다.");
    }

    /** D44: gap = round(0.05 × 100) = 5 px between the two cells only; the last cell ends at the edge. */
    @Test
    void cellRects_rowWithGap() {
        CollageLayout layout = new CollageLayout(CollageShape.ROW, null, List.of(List.of(0.5, 0.5)), 0.05,
                "#FFFFFF", Boolean.TRUE, List.of(CollageLayouts.initialCell(1L), CollageLayouts.initialCell(2L)));

        assertThat(CollageLayouts.cellRects(layout, 200, 100)).containsExactly(
                new CollageLayouts.Rect(0, 0, 98, 100), new CollageLayouts.Rect(103, 0, 97, 100));
    }
}
