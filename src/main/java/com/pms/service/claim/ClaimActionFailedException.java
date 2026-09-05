package com.pms.service.claim;

import com.pms.dto.response.ClaimActionResponse;

/**
 * 쿠팡이 액션을 거절했을 때 (FEATURE_2609_21 / PLAN D15).
 *
 * <p>단건 액션이라 실패를 결과 객체가 아니라 예외로 다룬다 — 일괄용 집계 스키마를 쓰면 "성공 0건"을
 * 200 으로 돌려주는 애매한 응답이 된다. {@link com.pms.exception.GlobalExceptionHandler} 가 <b>502</b> 로
 * 매핑하며 쿠팡 원문({@code resultCode}/{@code resultMessage})을 {@code data} 에 그대로 싣는다.
 */
public class ClaimActionFailedException extends RuntimeException {

    private final transient ClaimActionResponse result;

    public ClaimActionFailedException(ClaimActionResponse result) {
        super("쿠팡 처리 실패: code=" + result.resultCode() + " message=" + result.resultMessage());
        this.result = result;
    }

    public ClaimActionResponse getResult() {
        return result;
    }
}
