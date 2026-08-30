package com.pms.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure sanitizer boundaries — no Spring. */
class TextStyleSupportTest {

    @Test
    void hex_acceptsSixDigit_rejectsRest() {
        assertThat(TextStyleSupport.hex("#ff0000")).isEqualTo("#ff0000");
        assertThat(TextStyleSupport.hex("#zzz")).isNull();
        assertThat(TextStyleSupport.hex("red")).isNull();
        assertThat(TextStyleSupport.hex(null)).isNull();
    }

    @Test
    void intPx_clampsToBounds() {
        assertThat(TextStyleSupport.intPx("5", 8, 200)).isEqualTo("8px");
        assertThat(TextStyleSupport.intPx("999", 8, 200)).isEqualTo("200px");
        assertThat(TextStyleSupport.intPx("18", 8, 200)).isEqualTo("18px");
    }

    @Test
    void intPx_nonInteger_isNull() {
        assertThat(TextStyleSupport.intPx("18px", 8, 200)).isNull();
        assertThat(TextStyleSupport.intPx(null, 8, 200)).isNull();
    }
}
