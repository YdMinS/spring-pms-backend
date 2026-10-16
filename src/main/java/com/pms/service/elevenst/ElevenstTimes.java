package com.pms.service.elevenst;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 11st request / response time formats (FEATURE_2610_17 / D32). Every 11st time is KST wall clock — values are
 * read and written as {@link LocalDateTime} without a zone.
 *
 * <p>Requests: order · claim lists and {@code sendDt} of 1888 · 6747 = {@code yyyyMMddHHmm} ({@link #MINUTE});
 * the cancel-reject {@code sendDt} (1641), Q&A and urgent-notice lists = {@code yyyyMMdd} ({@link #DAY}).
 * Responses: {@link #parse} accepts the shapes the guide shows ({@code yyyy-MM-dd HH:mm:ss},
 * {@code 2010-01-19 12:47:02.0}, {@code 20140929}) and returns null for anything else.
 */
public final class ElevenstTimes {

    public static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final List<DateTimeFormatter> DATE_TIMES = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DAY);

    private ElevenstTimes() {
    }

    /** @return the parsed KST wall-clock time, or null when blank or not one of the known shapes */
    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        for (DateTimeFormatter format : DATE_TIMES) {
            try {
                return LocalDateTime.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(text, format).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }
}
