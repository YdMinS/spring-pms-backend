package com.pms.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API 경계 문자열 → {@link Platform} 변환 (FEATURE_2609_26 / 01) — 순수 단위 테스트.
 *
 * 엔티티·서비스는 enum 만 쓰고 문자열은 컨트롤러/서비스 진입부에서 한 번만 해석한다.
 * 알 수 없는 값은 {@link IllegalArgumentException}(GlobalExceptionHandler → 400)이어야 한다.
 */
class PlatformTest {

    @Test
    void from_isCaseInsensitive() {
        assertThat(Platform.from("coupang")).isEqualTo(Platform.COUPANG);
        assertThat(Platform.from("Naver")).isEqualTo(Platform.NAVER);
    }

    @Test
    void from_trimsSurroundingWhitespace() {
        assertThat(Platform.from("  COUPANG  ")).isEqualTo(Platform.COUPANG);
    }

    @Test
    void from_unknownValue_throwsIllegalArgument() {
        assertThatThrownBy(() -> Platform.from("GMARKET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 플랫폼");
    }

    @Test
    void from_nullOrBlank_throwsIllegalArgument() {
        assertThatThrownBy(() -> Platform.from(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform 이 비어 있습니다");
        assertThatThrownBy(() -> Platform.from("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform 이 비어 있습니다");
    }
}
