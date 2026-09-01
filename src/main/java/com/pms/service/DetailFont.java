package com.pms.service;

/**
 * Resolved font for a detail block: the CSS font-family value + optional {@code @font-face} source.
 *
 * <p>{@code srcUrl} null = no downloadable binary (the {@code family} is a plain fallback stack).
 * {@code format} is the CSS {@code format()} hint ({@code truetype} / {@code opentype}).</p>
 */
public record DetailFont(Long id, String family, String srcUrl, String format) {
}
