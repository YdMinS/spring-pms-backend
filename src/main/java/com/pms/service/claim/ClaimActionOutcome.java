package com.pms.service.claim;

/**
 * 어댑터가 돌려주는 전송 결과 (FEATURE_2609_21 / PLAN D15).
 *
 * <p>HTTP 200 이어도 바디 {@code code} 가 200 이 아닐 수 있으므로 <b>바디를 읽고 판정</b>한 결과다.
 *
 * ⚠️ {@code resultCode}/{@code resultMessage} 는 <b>쿠팡 원문 그대로</b> — 번역·요약하면 실계정
 * 디버깅에서 검색이 안 된다.
 */
public record ClaimActionOutcome(boolean succeeded, String resultCode, String resultMessage) {
}
