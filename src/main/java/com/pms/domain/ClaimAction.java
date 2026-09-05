package com.pms.domain;

/**
 * 클레임(반품·교환) 처리 액션 — <b>사용자 의도</b>의 이름이다 (FEATURE_2609_21 / PLAN D14·D18).
 *
 * <p>플랫폼 API 의 이름이 아니다. {@code RETURN_APPROVE} 는 쿠팡에서
 * {@code returnRequests/{id}/approval} 이지만 네이버에서는 다른 것일 수 있다 — 경로·파라미터·상태
 * 조건은 전부 어댑터({@code ClaimActionAdapter} 구현) 안에 산다. enum 에 {@code COUPANG_} 접두를
 * 붙이거나 플랫폼별 값을 나누면 UI·감사 테이블·응답 계약이 플랫폼 수만큼 갈라진다.
 *
 * <p><b>교환 4값은 구현보다 먼저 선언한다</b>(D14) — 나중에 enum 이 늘면 감사 테이블·프론트 매핑·
 * 테스트가 함께 움직인다. 아직 구현이 없는 값은 어댑터가 {@link UnsupportedOperationException} 을
 * 던지고 {@code availableActions} 에 실리지 않는다.
 *
 * <p>{@code label} 은 <b>기본 표시명</b>이다. 응답에 실리는 라벨은 어댑터가 정하며(D18), 지금은
 * 쿠팡 어댑터가 이 값을 그대로 쓴다.
 */
public enum ClaimAction {

    RETURN_RECEIVE_CONFIRM(ClaimType.RETURN, "반품 입고확인", Requires.NONE, false),
    /** 🔴 환불 확정 — 되돌릴 수 없다. */
    RETURN_APPROVE(ClaimType.RETURN, "반품 승인", Requires.NONE, true),
    RETURN_COLLECT_INVOICE(ClaimType.RETURN, "회수 송장 등록", Requires.INVOICE, false),

    EXCHANGE_RECEIVE_CONFIRM(ClaimType.EXCHANGE, "교환 입고확인", Requires.NONE, false),
    /** 🔴 되돌릴 수 없다. */
    EXCHANGE_REJECT(ClaimType.EXCHANGE, "교환 거부", Requires.REJECT_CODE, true),
    EXCHANGE_RESHIP_INVOICE(ClaimType.EXCHANGE, "재발송 송장 등록", Requires.INVOICE, false),
    EXCHANGE_COLLECT_INVOICE(ClaimType.EXCHANGE, "회수 송장 등록", Requires.INVOICE, false);

    /**
     * 액션이 요구하는 추가 입력. 요청 DTO 검증이 이 값 하나로 갈린다(서비스가 아니라 액션 메타가 정한다).
     *
     * <p>⚠️ 값을 늘리면 클라이언트 3벌이 함께 움직인다 — 클라이언트는 <b>모르는 {@code requires}
     * 값이면 버튼을 렌더하지 않는다</b>(PLAN §8). 그래서 새 값이 붙어도 구버전이 깨지지 않는다.
     */
    public enum Requires {
        /** 추가 입력 없음. */
        NONE,
        /** 택배사 코드 + 송장번호. */
        INVOICE,
        /** 거부 사유 코드(선택지는 어댑터가 {@code choices} 로 내려준다, D19). */
        REJECT_CODE
    }

    private final ClaimType claimType;
    private final String label;
    private final Requires requires;
    private final boolean irreversible;

    ClaimAction(ClaimType claimType, String label, Requires requires, boolean irreversible) {
        this.claimType = claimType;
        this.label = label;
        this.requires = requires;
        this.irreversible = irreversible;
    }

    public ClaimType getClaimType() {
        return claimType;
    }

    /** 기본 한글 표시명. 응답 라벨의 주인은 어댑터다(D18). */
    public String getLabel() {
        return label;
    }

    public Requires getRequires() {
        return requires;
    }

    /** 되돌릴 수 없는 액션인가 — UI 가 2단 확인을 띄우는 근거(D10). */
    public boolean isIrreversible() {
        return irreversible;
    }
}
