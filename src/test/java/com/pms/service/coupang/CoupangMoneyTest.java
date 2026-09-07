package com.pms.service.coupang;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 쿠팡 금액(protobuf Money) 파싱 (FEATURE_2609_26 / PLAN D9). */
class CoupangMoneyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void protobufMoney객체를파싱한다() throws Exception {
        // 실응답 형태 — {"nanos":0,"units":13670,"currencyCode":"KRW"} (dev, 2026-09-07)
        assertThat(CoupangMoney.parse(objectMapper.readTree(
                "{\"nanos\":0,\"units\":13670,\"currencyCode\":\"KRW\"}")))
                .isEqualByComparingTo("13670");
    }

    @Test
    void nanos는10의마이너스9승단위로더해진다() throws Exception {
        assertThat(CoupangMoney.parse(objectMapper.readTree("{\"units\":1,\"nanos\":500000000}")))
                .isEqualByComparingTo("1.5");
    }

    @Test
    void 숫자스칼라도그대로받는다() throws Exception {
        assertThat(CoupangMoney.parse(objectMapper.readTree("26990"))).isEqualByComparingTo("26990");
    }

    @Test
    void null과알수없는형태는예외없이null이다() throws Exception {
        // 한 건의 형태 이상이 적재 회차 전체를 깨면 안 된다.
        assertThat(CoupangMoney.parse(null)).isNull();
        assertThat(CoupangMoney.parse(objectMapper.readTree("null"))).isNull();
        assertThat(CoupangMoney.parse(objectMapper.readTree("{\"currencyCode\":\"KRW\"}"))).isNull();
        assertThat(CoupangMoney.parse(objectMapper.readTree("[1,2]"))).isNull();
        assertThat(CoupangMoney.parse(objectMapper.readTree("\"not-a-number\""))).isNull();
    }
}
