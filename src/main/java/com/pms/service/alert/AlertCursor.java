package com.pms.service.alert;

import com.pms.domain.AlertType;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 알림 목록의 페이징 커서 (FEATURE_2609_51 / PLAN D12). 파싱·직렬화는 <b>여기 한 곳</b>에서만 한다.
 *
 * <p>커서는 {@code 발생시각|종류|id} 세 값이다 — 🔴 시각만으로는 부족하다: 같은 시각에 두 건이 들어오면
 * (동기화는 초 단위로 뭉쳐 들어온다) 한 건이 중복되거나 빠진다. 세 값이 곧 목록의 정렬 키
 * ({@code 발생시각 desc · 종류 asc · id desc})라, 커서 하나가 목록 위의 한 점을 정확히 가리킨다.
 *
 * <p>⚠️ 클라이언트에게는 <b>불투명 문자열</b>이다 — 만들지 않고 이전 응답 값을 그대로 돌려보낸다.
 * ⚠️ 형식이 깨졌으면 <b>예외를 던지지 않고</b> {@link Optional#empty()} 를 준다(= 첫 장부터).
 * 사용자는 오래된 링크를 눌렀을 뿐이고, 그것이 500 이 될 이유가 없다.
 */
public record AlertCursor(LocalDateTime occurredAt, AlertType alertType, Long refId) {

    private static final String DELIMITER = "|";

    /** {@code 2026-09-15T10:20:30|ORDER|123} */
    public String encode() {
        return occurredAt + DELIMITER + alertType.name() + DELIMITER + refId;
    }

    /** 형식이 깨졌거나 모르는 종류면 empty — 호출자는 첫 장을 준다. */
    public static Optional<AlertCursor> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        // -1 을 쓰지 않는다: 세 토막이 정확히 나와야 하고, 더 많으면 우리가 만든 커서가 아니다.
        String[] parts = raw.split("\\" + DELIMITER);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AlertCursor(
                    LocalDateTime.parse(parts[0]),
                    AlertType.valueOf(parts[1]),
                    Long.parseLong(parts[2])));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
