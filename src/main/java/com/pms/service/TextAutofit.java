package com.pms.service;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure text auto-fit: pick the largest font size (within [min,max]) whose word-wrapped lines fit the
 * available width and height in at most {@code maxLines}. If nothing fits even at {@code minFontSize},
 * fall back to min size and ellipsize (…) the last line.
 *
 * <p>This is the core, business-critical layout logic of the thumbnail feature, kept as a
 * {@link FontRenderContext}-based pure function (no {@code Graphics}) so it is unit-testable in
 * isolation (see {@code TextAutofitTest}). {@link ThumbnailRenderer} calls it, then paints the result.</p>
 */
public final class TextAutofit {

    private TextAutofit() {
    }

    /**
     * @param fontSize   chosen size
     * @param lines      wrapped (and possibly ellipsized) lines
     * @param ascent     baseline ascent at {@code fontSize}
     * @param lineHeight full line height at {@code fontSize}
     */
    public record Result(int fontSize, List<String> lines, int ascent, int lineHeight) {
    }

    public static Result fit(String text, Font baseFont, int availW, int availH,
                             int maxFontSize, int minFontSize, int maxLines,
                             FontRenderContext frc) {
        String safe = text == null ? "" : text.trim();
        int lines = Math.max(1, maxLines);

        for (int size = maxFontSize; size >= minFontSize; size--) {
            Font font = baseFont.deriveFont((float) size);
            int lineHeight = lineHeight(font, frc);
            List<String> wrapped = wrap(safe, font, frc, availW);
            if (wrapped.size() <= lines && wrapped.size() * lineHeight <= availH) {
                return new Result(size, wrapped, ascent(font, frc), lineHeight);
            }
        }

        // Doesn't fit even at min size → clamp to what fits and ellipsize the last visible line.
        Font font = baseFont.deriveFont((float) minFontSize);
        int lineHeight = lineHeight(font, frc);
        List<String> wrapped = wrap(safe, font, frc, availW);
        int maxByHeight = Math.max(1, availH / Math.max(1, lineHeight));
        int visible = Math.min(lines, maxByHeight);
        if (wrapped.size() > visible) {
            wrapped = new ArrayList<>(wrapped.subList(0, visible));
            int last = wrapped.size() - 1;
            wrapped.set(last, ellipsize(wrapped.get(last), font, frc, availW));
        } else if (!wrapped.isEmpty()) {
            // fit within line budget but a single line may still exceed width at min size
            int last = wrapped.size() - 1;
            if (width(wrapped.get(last), font, frc) > availW) {
                wrapped = new ArrayList<>(wrapped);
                wrapped.set(last, ellipsize(wrapped.get(last), font, frc, availW));
            }
        }
        return new Result(minFontSize, wrapped, ascent(font, frc), lineHeight);
    }

    /** Greedy word wrap; a single word wider than {@code availW} is split by characters. */
    static List<String> wrap(String text, Font font, FontRenderContext frc, int availW) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (width(candidate, font, frc) <= availW) {
                current = new StringBuilder(candidate);
                continue;
            }
            if (current.length() > 0) {
                out.add(current.toString());
                current = new StringBuilder();
            }
            if (width(word, font, frc) > availW) {
                String remainder = word;
                while (remainder.length() > 1 && width(remainder, font, frc) > availW) {
                    int cut = breakByChar(remainder, font, frc, availW);
                    out.add(remainder.substring(0, cut));
                    remainder = remainder.substring(cut);
                }
                current = new StringBuilder(remainder);
            } else {
                current = new StringBuilder(word);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    /** Largest prefix length of {@code s} whose width fits {@code availW} (at least 1 char). */
    private static int breakByChar(String s, Font font, FontRenderContext frc, int availW) {
        int cut = 1;
        while (cut < s.length() && width(s.substring(0, cut + 1), font, frc) <= availW) {
            cut++;
        }
        return cut;
    }

    /** Trim {@code s} and append … until it fits {@code availW}. */
    private static String ellipsize(String s, Font font, FontRenderContext frc, int availW) {
        String ellipsis = "…";
        if (width(ellipsis, font, frc) > availW) {
            return ellipsis;
        }
        String trimmed = s;
        while (!trimmed.isEmpty() && width(trimmed + ellipsis, font, frc) > availW) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }

    private static int width(String s, Font font, FontRenderContext frc) {
        return (int) Math.ceil(font.getStringBounds(s, frc).getWidth());
    }

    private static int lineHeight(Font font, FontRenderContext frc) {
        LineMetrics lm = font.getLineMetrics("Ayg", frc);
        return (int) Math.ceil(lm.getHeight());
    }

    private static int ascent(Font font, FontRenderContext frc) {
        LineMetrics lm = font.getLineMetrics("Ayg", frc);
        return (int) Math.ceil(lm.getAscent());
    }
}
