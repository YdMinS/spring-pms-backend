package com.pms.service.coupang;

import com.pms.domain.CoupangAccountCredential;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;

/**
 * 계정에서 쿠팡 자격증명을 꺼내는 <b>단일 통로</b> (FEATURE_2609_26 / PLAN D15).
 *
 * <p><b>필수 규칙</b>: 쿠팡 API 를 호출하는 코드는 {@code account.getCoupangCredential()} 을 직접 읽지 말고
 * 반드시 이 헬퍼를 통과한다. 플랫폼 검증(네이버 계정으로 쿠팡을 부르는 실수)과 미설정 방어가 한 곳에 모인다.
 *
 * <p><b>사용 예</b>:
 * <pre>
 * // 단발 사용
 * String path = props.getOrdersheetsPath().replace("{vendorId}", CoupangCredentials.of(account).getVendorId());
 *
 * // 같은 메서드에서 여러 번 쓰면 지역변수로 한 번만 꺼낸다
 * var cred = CoupangCredentials.of(account);
 * body.put("vendorId", cred.getVendorId());
 * body.put("replyBy", cred.getVendorUserId());
 * </pre>
 *
 * <p>⚠️ 상태 없는 정적 유틸이다 — 빈으로 만들지 말 것.
 * ⚠️ {@link IllegalArgumentException} 은 GlobalExceptionHandler 가 400 으로 매핑한다.
 */
public final class CoupangCredentials {

    private CoupangCredentials() {
    }

    /**
     * 쿠팡 자격증명을 꺼낸다.
     *
     * @throws IllegalArgumentException 계정이 쿠팡이 아니거나(네이버 계정에 쿠팡 호출) 자격증명이 없을 때 — 400
     */
    public static CoupangAccountCredential of(MarketplaceAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("계정이 없습니다");
        }
        if (account.getPlatform() != Platform.COUPANG) {
            throw new IllegalArgumentException("쿠팡 계정이 아닙니다: " + account.getPlatform());
        }
        CoupangAccountCredential credential = account.getCoupangCredential();
        if (credential == null) {
            throw new IllegalArgumentException("쿠팡 자격증명 미설정 — 계정 설정을 완료하세요");
        }
        return credential;
    }
}
