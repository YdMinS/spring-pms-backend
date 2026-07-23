package com.pms.service.external;

import java.util.Optional;

/**
 * 런타임 로그레벨 토글이 허용된 외부연동 대상 화이트리스트.
 *
 * <p>임의 패키지의 로그레벨을 조작하지 못하도록 enum 으로 제한한다. 새 연동을 추가할 때만
 * 여기 한 줄을 더한다(예: {@code NAVER("네이버", "com.pms.service.naver")}).
 * key 는 enum name(대문자)이며, 웹/모바일 select 는 {@code /targets} API 로 이 enum 과 자동 동기화된다.
 */
public enum LoggingTarget {

    COUPANG("쿠팡", "com.pms.service.coupang");
    // NAVER("네이버", "com.pms.service.naver")  ← 착수 시 한 줄 추가

    private final String label;
    private final String loggerName;

    LoggingTarget(String label, String loggerName) {
        this.label = label;
        this.loggerName = loggerName;
    }

    public String label() {
        return label;
    }

    public String loggerName() {
        return loggerName;
    }

    /**
     * key(대소문자 무시)로 대상을 조회한다.
     *
     * @return 화이트리스트에 없으면 {@link Optional#empty()}
     */
    public static Optional<LoggingTarget> from(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (LoggingTarget t : values()) {
            if (t.name().equalsIgnoreCase(key)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
