package com.pms.service.listing;

/**
 * 심사 사유 조회 결과 (FEATURE_2609_74/D9). 저장하지 않는다 — [승인 새로고침] 응답에 실려 나가고 끝이다.
 *
 * @param state FOUND = 사유를 받았다 · NOT_FOUND = 이력 첫 쪽에 해당 기록이 없다(D28) · FAILED = 조회 실패(D19)
 * @param text  FOUND 일 때만 값이 있다. 그 외에는 null
 */
public record ReviewNote(State state, String text) {

    public enum State { FOUND, NOT_FOUND, FAILED }

    public static ReviewNote found(String text) {
        return new ReviewNote(State.FOUND, text);
    }

    public static ReviewNote notFound() {
        return new ReviewNote(State.NOT_FOUND, null);
    }

    public static ReviewNote failed() {
        return new ReviewNote(State.FAILED, null);
    }
}
