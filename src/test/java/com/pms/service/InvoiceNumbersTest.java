package com.pms.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 송장번호 정규화 규칙 (하이픈·공백 제거).
 *
 * <p>쿠팡이 하이픈 섞인 송장번호를 거부하므로, 사용자가 {@code 2558-2825-3026} 으로 입력해도
 * 저장·전송이 같은 값({@code 255828253026})이 되게 하는 단일 규칙이다.
 */
class InvoiceNumbersTest {

    @Test
    void normalize_stripsHyphensAndWhitespace() {
        assertThat(InvoiceNumbers.normalize("2558-2825-3026")).isEqualTo("255828253026");
        assertThat(InvoiceNumbers.normalize(" 2558 2825\t3026 ")).isEqualTo("255828253026");
        assertThat(InvoiceNumbers.normalize("2558-2825 3026")).isEqualTo("255828253026");
    }

    @Test
    void normalize_leavesCleanValueUnchanged() {
        assertThat(InvoiceNumbers.normalize("255828253026")).isEqualTo("255828253026");
    }

    @Test
    void normalize_keepsOtherCharacters() {
        // 🔴 구분자 말고는 버리지 않는다 — 유효성은 마켓이 판정한다(여기서 지우면 오타가 조용히 통과한다).
        assertThat(InvoiceNumbers.normalize("CJ-1234/56")).isEqualTo("CJ1234/56");
    }

    @Test
    void normalize_handlesNullAndBlank() {
        assertThat(InvoiceNumbers.normalize(null)).isNull();      // 없는 값은 빈 값이 아니다
        assertThat(InvoiceNumbers.normalize("")).isEmpty();
        assertThat(InvoiceNumbers.normalize("  -  ")).isEmpty();
    }

    @Test
    void normalizeRequired_rejectsValueThatBecomesEmpty() {
        assertThat(InvoiceNumbers.normalizeRequired("2558-2825-3026")).isEqualTo("255828253026");
        assertThatThrownBy(() -> InvoiceNumbers.normalizeRequired("---"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceNumbers.normalizeRequired(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
