package com.pms.service.collage;

import com.pms.domain.CollageCell;
import com.pms.domain.CollageLayout;
import com.pms.domain.CollageShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure collage rules (FEATURE_2610_21) — shapes per photo count (D30), the initial collage (D32 · D33 · D47), the
 * read-time adjustment to a changed composition (D11 · D43), save validation and the cell geometry.
 *
 * <p>⚠️ The web editor mirrors {@link #shapesFor}, {@link #initialLines}, {@link #cellRects} and
 * {@link #minZoom} in {@code src/domain/collage/collageGeometry.ts} — change both together.</p>
 */
public final class CollageLayouts {

    public static final double MIN_LINE = 0.1;
    public static final double MIN_MAIN = 0.1;
    public static final double MAX_MAIN = 0.9;
    public static final double MAX_GAP = 0.05;
    public static final double MAX_ZOOM = 4.0;
    public static final String DEFAULT_GAP_COLOR = "#FFFFFF";

    private static final double SUM_TOLERANCE = 0.001;
    private static final double MIN_TOLERANCE = 0.000001;
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private CollageLayouts() {
    }

    /** One cell rectangle in pixels. */
    public record Rect(int x, int y, int w, int h) {
    }

    /** Shapes allowed for {@code n} photos (D30): 2 → 2 shapes, 3 → 4, 4+ → 5; below 2 → none (D3). */
    public static List<CollageShape> shapesFor(int n) {
        if (n < 2) {
            return List.of();
        }
        if (n == 2) {
            return List.of(CollageShape.ROW, CollageShape.COLUMN);
        }
        if (n == 3) {
            return List.of(CollageShape.ROW, CollageShape.COLUMN, CollageShape.LEFT_BIG, CollageShape.TOP_BIG);
        }
        return List.of(CollageShape.ROW, CollageShape.COLUMN, CollageShape.LEFT_BIG, CollageShape.TOP_BIG,
                CollageShape.TWO_ROWS);
    }

    /**
     * The first collage of a stage (D32 · D47): ROW, equal cells, no gap, white gap colour, template on, cells
     * assigned from the first cell in the given order (callers pass product ids ascending — D12).
     */
    public static CollageLayout initial(List<Long> productIds) {
        return new CollageLayout(CollageShape.ROW, initialMain(CollageShape.ROW),
                initialLines(CollageShape.ROW, productIds.size()), 0.0, DEFAULT_GAP_COLOR, Boolean.TRUE,
                productIds.stream().map(CollageLayouts::initialCell).toList());
    }

    /** A cell at its initial zoom and position (D33): zoom 1, centre 0.5 · 0.5. */
    public static CollageCell initialCell(Long productId) {
        return new CollageCell(productId, 1.0, 0.5, 0.5);
    }

    /** Initial big-cell ratio (D47): 0.5, or null for ROW / COLUMN. */
    public static Double initialMain(CollageShape shape) {
        return shape == CollageShape.ROW || shape == CollageShape.COLUMN ? null : 0.5;
    }

    /** Initial line ratios of {@code shape} for {@code n} photos — every cell of a line the same size (D47). */
    public static List<List<Double>> initialLines(CollageShape shape, int n) {
        return switch (shape) {
            case ROW, COLUMN -> List.of(equal(n));
            case LEFT_BIG, TOP_BIG -> List.of(equal(n - 1));
            case TWO_ROWS -> List.of(equal((n + 1) / 2), equal(n / 2));
        };
    }

    /**
     * The stored collage adjusted to the current components (D11 · D43) — computed on every read, the stored row is
     * not rewritten.
     *
     * <ul>
     *   <li>Same products → the stored collage as-is.</li>
     *   <li>Same count, other products → each vacated cell (in cell order) takes a new product (ascending id) at
     *       zoom 1 · centre; shape, lines, main, gap, gapColor, useTemplate kept.</li>
     *   <li>Count changed → the remaining products keep their cell order, zoom and position and move to the front;
     *       new products follow (ascending id) at zoom 1 · centre; lines and main back to their initial values;
     *       a shape the new count does not allow becomes ROW; gap, gapColor, useTemplate kept.</li>
     * </ul>
     *
     * @param saved        the stored collage (enabled stage)
     * @param componentIds current component product ids, ascending (at least 2 — callers apply D3 first)
     */
    public static CollageLayout adjust(CollageLayout saved, List<Long> componentIds) {
        Set<Long> current = new HashSet<>(componentIds);
        List<CollageCell> cells = saved.cells();
        Set<Long> stored = new HashSet<>();
        for (CollageCell cell : cells) {
            stored.add(cell.productId());
        }
        List<Long> added = componentIds.stream().filter(id -> !stored.contains(id)).toList();
        if (cells.size() == componentIds.size()) {
            if (added.isEmpty()) {
                return saved;
            }
            List<CollageCell> replaced = new ArrayList<>(cells.size());
            int next = 0;
            for (CollageCell cell : cells) {
                if (current.contains(cell.productId())) {
                    replaced.add(cell);
                } else {
                    replaced.add(initialCell(added.get(next)));
                    next++;
                }
            }
            return new CollageLayout(saved.shape(), saved.main(), saved.lines(), saved.gap(), saved.gapColor(),
                    saved.useTemplate(), replaced);
        }
        List<CollageCell> kept = new ArrayList<>();
        for (CollageCell cell : cells) {
            if (current.contains(cell.productId())) {
                kept.add(cell);
            }
        }
        for (Long id : added) {
            kept.add(initialCell(id));
        }
        int n = componentIds.size();
        CollageShape shape = shapesFor(n).contains(saved.shape()) ? saved.shape() : CollageShape.ROW;
        return new CollageLayout(shape, initialMain(shape), initialLines(shape, n), saved.gap(), saved.gapColor(),
                saved.useTemplate(), kept);
    }

    /**
     * Save / preview validation against the current components. Throws {@link IllegalArgumentException} (→400)
     * with a screen message on the first problem found.
     */
    public static void validate(CollageLayout layout, List<Long> componentIds) {
        if (layout == null) {
            throw new IllegalArgumentException("콜라주 값이 없습니다.");
        }
        int n = componentIds.size();
        if (n < 2) {
            throw new IllegalArgumentException("구성품이 2개 이상인 마스터만 콜라주를 만들 수 있습니다.");
        }
        if (layout.shape() == null || !shapesFor(n).contains(layout.shape())) {
            throw new IllegalArgumentException("이 사진 수에 쓸 수 없는 형태입니다: " + layout.shape());
        }
        List<CollageCell> cells = layout.cells();
        if (cells == null || cells.size() != n || cells.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("칸의 물품이 구성품과 다릅니다.");
        }
        Set<Long> ids = new HashSet<>();
        for (CollageCell cell : cells) {
            ids.add(cell.productId());
        }
        if (!ids.equals(new HashSet<>(componentIds))) {
            throw new IllegalArgumentException("칸의 물품이 구성품과 다릅니다.");
        }
        validateLines(layout.lines(), initialLines(layout.shape(), n));
        boolean hasMain = initialMain(layout.shape()) != null;
        if (hasMain ? layout.main() == null || layout.main() < MIN_MAIN - MIN_TOLERANCE
                || layout.main() > MAX_MAIN + MIN_TOLERANCE : layout.main() != null) {
            throw new IllegalArgumentException("큰 칸 비율이 올바르지 않습니다.");
        }
        if (layout.gap() == null || layout.gap() < 0 || layout.gap() > MAX_GAP + MIN_TOLERANCE) {
            throw new IllegalArgumentException("간격은 0~5% 사이여야 합니다.");
        }
        if (layout.gapColor() == null || !HEX_COLOR.matcher(layout.gapColor()).matches()) {
            throw new IllegalArgumentException("간격 색은 #RRGGBB 형식이어야 합니다.");
        }
        if (layout.useTemplate() == null) {
            throw new IllegalArgumentException("틀 함께 쓰기 값이 없습니다.");
        }
        for (CollageCell cell : cells) {
            if (cell.zoom() == null || cell.zoom() <= 0 || cell.zoom() > MAX_ZOOM + MIN_TOLERANCE
                    || !unit(cell.cx()) || !unit(cell.cy())) {
                throw new IllegalArgumentException("칸의 확대·위치 값이 올바르지 않습니다.");
            }
        }
    }

    /**
     * Cell rectangles in cell order for a {@code width} × {@code height} collage. The gap is
     * {@code round(gap × short side)} pixels between neighbouring cells only (no outer border, D44). A line splits
     * its length minus the gaps by cumulative rounding, so the last cell always ends at the line end.
     */
    public static List<Rect> cellRects(CollageLayout layout, int width, int height) {
        int gapPx = (int) Math.round(layout.gap() * Math.min(width, height));
        List<List<Double>> lines = layout.lines();
        List<Rect> rects = new ArrayList<>();
        switch (layout.shape()) {
            case ROW -> rects.addAll(splitX(0, 0, width, height, lines.get(0), gapPx));
            case COLUMN -> rects.addAll(splitY(0, 0, width, height, lines.get(0), gapPx));
            case LEFT_BIG -> {
                int bigW = (int) Math.round((width - gapPx) * layout.main());
                rects.add(new Rect(0, 0, bigW, height));
                int x = bigW + gapPx;
                rects.addAll(splitY(x, 0, width - x, height, lines.get(0), gapPx));
            }
            case TOP_BIG -> {
                int bigH = (int) Math.round((height - gapPx) * layout.main());
                rects.add(new Rect(0, 0, width, bigH));
                int y = bigH + gapPx;
                rects.addAll(splitX(0, y, width, height - y, lines.get(0), gapPx));
            }
            case TWO_ROWS -> {
                int topH = (int) Math.round((height - gapPx) * layout.main());
                rects.addAll(splitX(0, 0, width, topH, lines.get(0), gapPx));
                int y = topH + gapPx;
                rects.addAll(splitX(0, y, width, height - y, lines.get(1), gapPx));
            }
        }
        return rects;
    }

    /**
     * Lowest zoom of a photo in a cell (D33): the zoom at which the whole photo fits the cell, relative to zoom 1 =
     * the smallest size that covers the cell.
     */
    public static double minZoom(int cellW, int cellH, int photoW, int photoH) {
        double cover = Math.max((double) cellW / photoW, (double) cellH / photoH);
        double contain = Math.min((double) cellW / photoW, (double) cellH / photoH);
        return contain / cover;
    }

    private static void validateLines(List<List<Double>> lines, List<List<Double>> expected) {
        if (lines == null || lines.size() != expected.size()) {
            throw new IllegalArgumentException("칸 크기 비율이 올바르지 않습니다.");
        }
        for (int i = 0; i < lines.size(); i++) {
            List<Double> line = lines.get(i);
            if (line == null || line.size() != expected.get(i).size()) {
                throw new IllegalArgumentException("칸 크기 비율이 올바르지 않습니다.");
            }
            double sum = 0;
            for (Double v : line) {
                if (v == null || v < MIN_LINE - MIN_TOLERANCE) {
                    throw new IllegalArgumentException("칸 크기 비율이 올바르지 않습니다.");
                }
                sum += v;
            }
            if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
                throw new IllegalArgumentException("칸 크기 비율이 올바르지 않습니다.");
            }
        }
    }

    private static boolean unit(Double v) {
        return v != null && v >= 0 && v <= 1;
    }

    private static List<Double> equal(int k) {
        return Collections.nCopies(k, 1.0 / k);
    }

    private static List<Rect> splitX(int x, int y, int width, int height, List<Double> ratios, int gapPx) {
        List<Rect> out = new ArrayList<>();
        for (int[] seg : segments(x, width, ratios, gapPx)) {
            out.add(new Rect(seg[0], y, seg[1], height));
        }
        return out;
    }

    private static List<Rect> splitY(int x, int y, int width, int height, List<Double> ratios, int gapPx) {
        List<Rect> out = new ArrayList<>();
        for (int[] seg : segments(y, height, ratios, gapPx)) {
            out.add(new Rect(x, seg[0], width, seg[1]));
        }
        return out;
    }

    /** {start, size} per ratio along one line. */
    private static List<int[]> segments(int start, int length, List<Double> ratios, int gapPx) {
        int k = ratios.size();
        int avail = length - gapPx * (k - 1);
        double total = 0;
        for (Double r : ratios) {
            total += r;
        }
        List<int[]> out = new ArrayList<>(k);
        double cumulative = 0;
        int prevEnd = 0;
        for (int i = 0; i < k; i++) {
            cumulative += ratios.get(i);
            int end = i == k - 1 ? avail : (int) Math.round(avail * cumulative / total);
            out.add(new int[]{start + prevEnd + i * gapPx, end - prevEnd});
            prevEnd = end;
        }
        return out;
    }
}
