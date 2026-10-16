package com.pms.service.elevenst;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** FEATURE_2610_17 / D32: the response time shapes the guide shows, read as KST wall clock. */
class ElevenstTimesTest {

    @Test
    void parse_knownShapes() {
        assertThat(ElevenstTimes.parse("2026-10-15 09:30:00")).isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 30));
        assertThat(ElevenstTimes.parse("2010-01-19 12:47:02.0")).isEqualTo(LocalDateTime.of(2010, 1, 19, 12, 47, 2));
        assertThat(ElevenstTimes.parse("20140929")).isEqualTo(LocalDateTime.of(2014, 9, 29, 0, 0));
    }

    @Test
    void parse_unknownOrBlank_isNull() {
        assertThat(ElevenstTimes.parse("")).isNull();
        assertThat(ElevenstTimes.parse("10/15/2026")).isNull();
    }
}
