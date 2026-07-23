package com.pms.service.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PiiMasker 단위 테스트 (실제 ObjectMapper).
 *
 * 핵심 안전요건 2건만: 중첩 PII 치환, 비JSON 원문 미노출.
 */
class PiiMaskerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PiiMasker piiMasker = new PiiMasker(objectMapper);

    @Test
    void mask_중첩PII치환() throws Exception {
        String raw = "{\"receiver\":{\"name\":\"김철수\",\"safeNumber\":\"+8210...\","
                + "\"addr1\":\"서울\",\"addr2\":\"101동\",\"postCode\":\"12345\"}}";

        JsonNode masked = objectMapper.readTree(piiMasker.mask(raw));
        JsonNode receiver = masked.get("receiver");

        assertThat(receiver.get("name").asText()).isEqualTo("***");
        assertThat(receiver.get("safeNumber").asText()).isEqualTo("***");
        assertThat(receiver.get("addr1").asText()).isEqualTo("***");
        assertThat(receiver.get("addr2").asText()).isEqualTo("***");
        // Non-PII field keeps its original value.
        assertThat(receiver.get("postCode").asText()).isEqualTo("12345");
    }

    @Test
    void mask_비JSON_원문미노출() {
        assertThat(piiMasker.mask("not json")).isEqualTo("[UNPARSEABLE-MASKED]");
    }
}
