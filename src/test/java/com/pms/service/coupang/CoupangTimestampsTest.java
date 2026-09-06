package com.pms.service.coupang;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CoupangTimestamps — 쿠팡 시각 문자열 → KST naive LocalDateTime (순수 단위테스트).
 *
 * 오프셋 케이스가 이 클래스의 존재 이유다(2026-09-06 운영 반품 유실) — 지우지 말 것.
 */
class CoupangTimestampsTest {

    @Test
    void parse_offsetKst_keepsWallClock() {
        // 운영에서 실제로 온 값 — 옛 파서는 여기서 null 을 반환해 반품이 전건 유실됐다.
        assertThat(CoupangTimestamps.parse("2026-09-02T15:31:25+09:00"))
                .isEqualTo(LocalDateTime.of(2026, 9, 2, 15, 31, 25));
    }

    @Test
    void parse_otherOffset_convertsToKst() {
        // UTC(=+00:00) 09:00 은 KST 18:00 — 오프셋을 버리지 않고 환산한다.
        assertThat(CoupangTimestamps.parse("2026-09-02T09:00:00Z"))
                .isEqualTo(LocalDateTime.of(2026, 9, 2, 18, 0, 0));
    }

    @Test
    void parse_localFormats_areStillAccepted() {
        assertThat(CoupangTimestamps.parse("2026-09-01T10:20:30"))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 20, 30));
        assertThat(CoupangTimestamps.parse("2026-09-01 10:20:30"))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 20, 30));
        // ISO_LOCAL_DATE_TIME 이라 소수점 초도 함께 받는다.
        assertThat(CoupangTimestamps.parse("2026-09-01T10:20:30.123"))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 20, 30, 123_000_000));
    }

    @Test
    void parse_blankOrUnparsable_returnsNull() {
        assertThat(CoupangTimestamps.parse(null)).isNull();
        assertThat(CoupangTimestamps.parse("   ")).isNull();
        assertThat(CoupangTimestamps.parse("01/09/2026 오전 10시")).isNull();
    }
}
