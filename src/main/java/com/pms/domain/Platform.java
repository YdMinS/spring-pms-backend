package com.pms.domain;

/**
 * 판매 채널(마켓플레이스) 식별자 (FEATURE_2609_26 / PLAN D16).
 *
 * <p><b>공통 타입 — 새로 String 을 만들지 말 것.</b> 11개 테이블(`order_item`·`order_claim`·
 * `customer_inquiry`·`marketplace_account`·`product_listing`·`category`·`category_mapping`·
 * `platform_category`·`commission_rate`·`margin_policy`·`platform_carrier_code`)이 이 타입을 공유한다.
 *
 * <p><b>DB</b>: `@Enumerated(EnumType.STRING)` 이라 저장 값은 기존 문자열("COUPANG") 그대로다 —
 * 컬럼은 VARCHAR 를 유지하고 changeset·백필이 필요 없다.
 *
 * <p><b>경계 규칙</b>: 엔티티·리포지토리·서비스 내부는 전부 이 enum 이고,
 * API 경계(Request/Response DTO)만 `String` 이다. 컨트롤러/서비스 진입부에서 {@link #from(String)} 으로
 * 즉시 변환하고, 응답 매핑에서만 {@code .name()} 으로 되돌린다. 중간층에 String 을 남기지 않는다.
 *
 * <p>사용 예:
 * <pre>
 * // 컨트롤러 필수 파라미터
 * service.browse(Platform.from(platform), parentCode);
 *
 * // required = false / optional DTO 필드 (from(null) 은 400 을 던진다)
 * Platform p = (platform == null) ? null : Platform.from(platform);
 *
 * // 응답 매핑
 * .platform(entity.getPlatform().name())
 * </pre>
 *
 * <p>⚠️ {@code NAVER} 는 값만 선언돼 있다 — 어댑터·테이블은 다음 기능(PLAN D22)이라
 * 파싱은 되지만 resolver 단계에서 미지원으로 걸린다.
 */
public enum Platform {
    COUPANG,
    NAVER;

    /**
     * API 경계에서 문자열을 받을 때만 쓴다. 알 수 없는 값은 400.
     *
     * <p>{@link IllegalArgumentException} 은 GlobalExceptionHandler 에서 400 으로 매핑된다 —
     * 새 예외 타입을 도입하지 말 것.
     */
    public static Platform from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("platform 이 비어 있습니다");
        }
        try {
            return Platform.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 플랫폼: " + raw);
        }
    }
}
