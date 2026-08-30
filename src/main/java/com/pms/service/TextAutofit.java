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
                             double lineSpacing, FontRenderContext frc) {
        String safe = text == null ? "" : text.trim();
        int lines = Math.max(1, maxLines);

        for (int size = maxFontSize; size >= minFontSize; size--) {
            Font font = baseFont.deriveFont((float) size);
            int effLH = effectiveLineHeight(font, frc, lineSpacing);
            // Require every word to fit its line at this size: a size that would only fit by
            // splitting a word across lines is rejected so the fitter keeps shrinking. This keeps
            // words intact (e.g. "더모먼트그린 녹차라떼" wraps between words, never mid-word).
            // Only the min-size fallback below may split a word that never fits (very long token).
            if (allWordsFit(safe, font, frc, availW)) {
                List<String> wrapped = wrap(safe, font, frc, availW);
                if (wrapped.size() <= lines && wrapped.size() * effLH <= availH) {
                    return new Result(size, wrapped, ascent(font, frc), effLH);
                }
            }
        }

        // Doesn't fit even at min size → clamp to what fits and ellipsize the last visible line.
        Font font = baseFont.deriveFont((float) minFontSize);
        int effLH = effectiveLineHeight(font, frc, lineSpacing);
        List<String> wrapped = wrap(safe, font, frc, availW);
        int maxByHeight = Math.max(1, availH / Math.max(1, effLH));
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
        return new Result(minFontSize, wrapped, ascent(font, frc), effLH);
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

    /**
     * True if every whitespace-separated word fits {@code availW} at this font, so {@link #wrap} will
     * never split a word by characters. An empty/blank string trivially satisfies this.
     */
    private static boolean allWordsFit(String text, Font font, FontRenderContext frc, int availW) {
        for (String word : text.split("\\s+")) {
            if (!word.isEmpty() && width(word, font, frc) > availW) {
                return false;
            }
        }
        return true;
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

    /**
     * Natural line height scaled by {@code lineSpacing} (clamped to &gt;= 1.0 so lines never overlap).
     * Reflected in both the height budget and {@link Result#lineHeight()} so the renderer stack is
     * unchanged; {@code lineSpacing == 1.0} yields the natural height (today's rendering).
     */
    private static int effectiveLineHeight(Font font, FontRenderContext frc, double lineSpacing) {
        int naturalLH = lineHeight(font, frc);
        return Math.max(naturalLH, (int) Math.ceil(naturalLH * Math.max(1.0, lineSpacing)));
    }

    private static int ascent(Font font, FontRenderContext frc) {
        LineMetrics lm = font.getLineMetrics("Ayg", frc);
        return (int) Math.ceil(lm.getAscent());
    }
}
