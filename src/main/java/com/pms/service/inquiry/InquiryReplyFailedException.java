package com.pms.service.inquiry;

/**
 * 플랫폼이 답변 전송을 <b>거절</b>했을 때 (FEATURE_2609_23 / 04 Step 3).
 *
 * {@link com.pms.exception.GlobalExceptionHandler} 가 <b>400</b> 으로 매핑하고 메시지를 그대로 내려보내
 * 화면이 쿠팡 원문을 띄울 수 있게 한다(잘못된 {@code replyBy} · 삭제된 문의 · 중복 답변 · 빈 본문 ·
 * 길이 초과 · 상담 종료됨).
 *
 * <p>🔴 <b>타임아웃·네트워크 오류와 섞지 말 것</b> — 그쪽은 전송 성공 여부를 알 수 없어 502 다
 * (사용자가 재시도해도 되는지가 다르다). 이 예외는 "쿠팡까지 갔고 쿠팡이 안 받았다" 는 뜻이다.
 */
public class InquiryReplyFailedException extends RuntimeException {

    public InquiryReplyFailedException(String message) {
        super(message);
    }
}
