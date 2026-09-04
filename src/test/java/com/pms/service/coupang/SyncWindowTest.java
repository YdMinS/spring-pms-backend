package com.pms.service.coupang;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SyncWindow 검증 — 쿠팡 조회 창의 유일한 생성/검증 지점(FEATURE_2609_10 D6·D7).
 */
class SyncWindowTest {

    @Test
    void from이_to보다_늦으면_거절() {
        assertThatThrownBy(() -> new SyncWindow(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시작일");
    }

    @Test
    void 간격_31일은_거절_30일은_통과() {
        LocalDate from = LocalDate.of(2026, 8, 1);

        // 쿠팡: "endTime-startTime range should less than 31" → 31일 차이는 이미 초과다.
        assertThatThrownBy(() -> new SyncWindow(from, from.plusDays(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("31일");

        assertThatCode(() -> new SyncWindow(from, from.plusDays(30))).doesNotThrowAnyException();
    }

    @Test
    void null은_거절() {
        assertThatThrownBy(() -> new SyncWindow(null, LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncWindow(LocalDate.of(2026, 8, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // recentSince: lastSuccess 는 서버 시각(UTC) naive 라 UTC 로 해석된다 → 테스트 데이터도 UTC 로 고정한다.
    // KST 기준으로 만들면(LocalDateTime.now() 는 로컬=KST) 환산에서 날짜가 하루 당겨져 오후에만 깨진다.
    private static LocalDateTime utcDaysAgo(long days) {
        return LocalDate.now(SyncWindow.KST).minusDays(days).atTime(3, 0);   // UTC 03:00 = KST 12:00
    }

    private static long lengthInDays(SyncWindow window) {
        return ChronoUnit.DAYS.between(window.from(), window.to());
    }

    @Test
    void 마지막성공이없으면상한창() {
        // 한 번도 성공 못 한 계정 = 현행 동작 그대로(D4).
        assertThat(lengthInDays(SyncWindow.recentSince(null, 3, 14))).isEqualTo(14);
    }

    @Test
    void 어제성공했으면하한창() {
        assertThat(lengthInDays(SyncWindow.recentSince(utcDaysAgo(1), 3, 14))).isEqualTo(3);
    }

    @Test
    void 오래쉬었으면경과만큼넓어지되상한까지() {
        // 상한(sync-days)을 넘지 않는다 — 어떤 경로에서도 현행보다 넓어지지 않는다(D4).
        assertThat(lengthInDays(SyncWindow.recentSince(utcDaysAgo(20), 3, 14))).isEqualTo(14);
    }

    @Test
    void 일주일쉬었으면8일창() {
        // 경과 7일 + 1 = 8일. clamp 에 걸리지 않는 유일한 케이스라 UTC 고정이 특히 중요하다.
        assertThat(lengthInDays(SyncWindow.recentSince(utcDaysAgo(7), 3, 14))).isEqualTo(8);
    }

    @Test
    void recent는_KST_오늘_기준() {
        SyncWindow window = SyncWindow.recent(14);

        LocalDate today = LocalDate.now(SyncWindow.KST);
        assertThat(window.to()).isEqualTo(today);
        assertThat(window.from()).isEqualTo(today.minusDays(14));
    }
}
