package com.pms.service.coupang;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

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

    @Test
    void recent는_KST_오늘_기준() {
        SyncWindow window = SyncWindow.recent(14);

        LocalDate today = LocalDate.now(SyncWindow.KST);
        assertThat(window.to()).isEqualTo(today);
        assertThat(window.from()).isEqualTo(today.minusDays(14));
    }
}
