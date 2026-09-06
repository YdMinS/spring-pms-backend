package com.pms.dto.response;

/**
 * 답변 가능 여부·제약 (FEATURE_2609_23 / PLAN D5 · 04 Step 2).
 *
 * 문의 <b>단건</b> 응답과 답변 전송 성공 응답에만 실린다(목록에는 없다 — 계정을 매 행마다 들여다봐야
 * 하고 목록에서는 쓰지 않는다).
 *
 * <p>🔴 판정은 서버 한 곳({@code InquiryReplyPolicy})에만 둔다. 유형마다 답변 규칙이 다르므로
 * (상품문의 1자~ · 고객센터 2자~ · {@code parentAnswerId} 필수) 클라이언트가 판정하면 웹·모바일 두 벌이
 * 어긋난다. {@code reason} 도 <b>서버가 완성된 문장</b>으로 준다 — 코드→문구 맵을 클라이언트가 가지면
 * 같은 사유가 화면마다 다르게 보인다(2609_21 D18 과 같은 계약).
 *
 * @param canReply      지금 답변을 보낼 수 있는가
 * @param reason        {@code canReply=false} 일 때만 채워지는 <b>사용자 노출 문구</b>
 * @param minLength     본문 최소 길이(strip 후)
 * @param maxLength     본문 최대 길이(strip 후)
 * @param once          true = 되돌릴 수 없음 → 클라이언트가 2단 확인을 건다(D17)
 * @param parentReplyId 고객센터 전용. 서버가 고른 값이며 클라이언트는 그대로 되돌려 보내기만 한다
 *                      (전송 시에도 <b>서버가 자기 값을 다시 고른다</b> — 신뢰하지 않는다)
 */
public record ReplyCapability(
        boolean canReply,
        String reason,
        int minLength,
        int maxLength,
        boolean once,
        String parentReplyId) {
}
