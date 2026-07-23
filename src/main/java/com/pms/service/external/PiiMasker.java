package com.pms.service.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 외부 API raw JSON 응답에서 개인정보(PII) 필드값만 마스킹하는 공용 컴포넌트.
 *
 * <p>로그로 나가는 <b>모든 경로</b>(성공 DEBUG raw · 실패 raw)에서 반드시 이 마스커를 통과시켜야 한다.
 * Jackson 트리로 파싱해 지정 필드명(대소문자 무시)을 중첩 전체 재귀 탐색하며 값을 {@code "***"} 로 치환한다.
 *
 * <p><b>안전요건</b>: JSON 파싱 실패 시 원문을 절대 반환하지 않고 {@code "[UNPARSEABLE-MASKED]"} 를 반환한다
 * (마스킹되지 않은 원문이 로그로 새어나가는 것을 차단).
 *
 * <p>쿠팡 전용이 아닌 shared 위치(service/external)에 둔다 — 향후 네이버 등도 재사용.
 *
 * <p>사용 예:
 * <pre>{@code
 * if (log.isDebugEnabled()) log.debug("resp={}", piiMasker.mask(rawBody));
 * }</pre>
 */
@Component
public class PiiMasker {

    /** 마스킹 대상 필드명 (소문자 정규화 후 비교). 쿠팡 receiver.name/safeNumber/addr1/addr2 등. */
    private static final Set<String> PII_FIELDS = Set.of(
            "name", "safenumber", "addr1", "addr2",
            "receivername", "receiveraddr", "phone", "phonenumber");

    private static final String MASK = "***";
    private static final String UNPARSEABLE = "[UNPARSEABLE-MASKED]";

    private final ObjectMapper objectMapper;

    public PiiMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * raw JSON String 의 PII 필드값을 마스킹해 반환한다.
     *
     * @param rawJson 외부 API raw 응답 (JSON 이 아닐 수도 있음)
     * @return PII 가 {@code "***"} 로 치환된 JSON. 파싱 실패 시 {@code "[UNPARSEABLE-MASKED]"}
     */
    public String mask(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            maskNode(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // Never leak the original body when it cannot be parsed/re-serialized.
            return UNPARSEABLE;
        }
    }

    /** 트리를 재귀 순회하며 PII 필드값을 치환한다. */
    private void maskNode(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (PII_FIELDS.contains(entry.getKey().toLowerCase())) {
                    obj.put(entry.getKey(), MASK);
                } else {
                    maskNode(entry.getValue());
                }
            }
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(this::maskNode);
        }
    }
}
