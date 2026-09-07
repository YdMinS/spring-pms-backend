package com.pms.service.coupang;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 쿠팡 응답의 타임스탬프 문자열 → 저장용 {@link LocalDateTime}(KST naive) 변환의 <b>단일 소유자</b>.
 *
 * <p><b>필수 사용 규칙</b>: 쿠팡 응답의 시각 문자열을 {@code LocalDateTime} 으로 바꾸는 곳은 전부
 * 이 클래스를 경유한다. 파서마다 {@code DateTimeFormatter} 목록을 따로 두면 한 곳만 고쳐지는 날이
 * 오고, 실제로 그렇게 됐다 — 2026-09-06 운영에서 반품 클레임이 전건 유실됐다(아래 배경).
 *
 * <p><b>파일 위치</b>: {@code com/pms/service/coupang/CoupangTimestamps.java}
 *
 * <p><b>사용 예제</b>:
 * <pre>
 * // 파서에서 (정적 호출 — 주입하지 않는다)
 * LocalDateTime receivedAt = CoupangTimestamps.parse(text(receipt, "createdAt"));
 * if (receivedAt == null) {
 *     log.warn("Skipping ... unparsable createdAt: ...");
 *     return List.of();               // nullable=false 컬럼이라 저장할 수 없다
 * }
 * </pre>
 *
 * <p><b>받는 포맷</b> (순서대로 시도, 전부 실패하면 {@code null}):
 * <ol>
 *   <li>오프셋 포함 ISO-8601 — {@code 2026-09-02T15:31:25+09:00}, {@code ...Z}.
 *       <b>같은 순간의 KST 벽시계 시각</b>으로 환산해 저장한다(오프셋은 버린다).</li>
 *   <li>오프셋 없는 ISO-8601 — {@code 2026-09-01T10:20:30}, {@code 2026-09-01T10:20:30.123}</li>
 *   <li>공백 구분 — {@code 2026-09-01 10:20:30}</li>
 * </ol>
 *
 * <p>⚠️ KST naive 로 저장하는 이유: {@code orders.ordered_at} 등 쿠팡 유래 시각이 전부 KST
 * naive 다. 여기서만 UTC 로 저장하면 같은 화면의 두 시각이 9시간 어긋난다.
 * (전역 타임스탬프 정합 정리는 별건 — 그 결론이 나면 이 클래스 하나만 바꾸면 된다.)
 *
 * <p>❌ 금지 패턴:
 * <ul>
 *   <li>파서·어댑터에 {@code TIMESTAMP_FORMATS} 를 다시 두는 것 — 그것이 이 클래스가 생긴 원인이다.</li>
 *   <li>파싱 실패를 예외로 올리는 것 — 한 건의 포맷 이상이 동기화 회차 전체를 깨면 안 된다.
 *       호출자가 {@code null} 을 보고 그 건만 건너뛰고 WARN 을 남긴다.</li>
 *   <li>실패를 {@code LocalDateTime.now()} 같은 대체값으로 메우는 것 — 접수일은 조회 창·추적
 *       슬라이스의 기준이라 지어내면 조용히 틀린 창이 만들어진다.</li>
 * </ul>
 *
 * <p><b>배경(2026-09-06)</b>: 반품 파서가 {@code yyyy-MM-dd'T'HH:mm:ss} / {@code yyyy-MM-dd HH:mm:ss}
 * 두 후보만 갖고 있었는데 쿠팡 v6 {@code returnRequests} 의 {@code createdAt} 실응답은
 * {@code 2026-09-02T15:31:25+09:00} 였다 → 전건 파싱 실패 → {@code order_claim} 미적재 →
 * 반품/교환 화면이 계속 비어 있었다. 취소 보정({@code cancel_count})은 같은 응답의 다른 경로라
 * 정상 동작했고, 적재 실패는 예외를 삼키는 자리라 <b>동기화는 성공으로 보였다</b>.
 */
public final class CoupangTimestamps {

    /** 오프셋이 없는 응답용 후보. ISO_LOCAL_DATE_TIME 이 소수점 초·초 생략까지 함께 받는다. */
    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    private CoupangTimestamps() {
    }

    /**
     * 쿠팡 시각 문자열 1개를 파싱한다.
     *
     * @param raw 응답 값(null·공백 허용)
     * @return KST naive {@code LocalDateTime}, 파싱할 수 없으면 {@code null}
     */
    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();

        try {
            // Offset-bearing ISO-8601 first: converting to KST keeps the instant correct even if
            // Coupang ever answers with an offset other than +09:00.
            return OffsetDateTime.parse(value).atZoneSameInstant(SyncWindow.KST).toLocalDateTime();
        } catch (Exception ignored) {
            // Not offset-bearing — fall through to the local formats.
        }

        for (DateTimeFormatter format : LOCAL_FORMATS) {
            try {
                return LocalDateTime.parse(value, format);
            } catch (Exception ignored) {
                // 다음 후보로
            }
        }
        return null;
    }
}
