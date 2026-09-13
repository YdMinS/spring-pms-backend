package com.pms.dto.response;

/**
 * 못 맞춘 바코드를 서버에 물어본 결과 (FEATURE_2609_40 / PLAN D11 · D12).
 *
 * <p>🔴 이 창구는 <b>오류 경로 전용</b>이다. 맞는 스캔은 화면이 로컬로 처리한다(D11) — 스캔마다 서버를
 * 부르면 작업이 끊긴다. 여기 오는 것은 이미 한 번 실패한 스캔이라 느려도 된다.
 *
 * <p>🔴 두 실패를 <b>구분해서</b> 돌려주는 것이 존재 이유다:
 * <pre>
 *   found = false                     → 「등록되지 않은 바코드」   (물품을 등록해야 한다)
 *   found = true, inThisParcel = false → 「이 주문에 없는 물품」    (잘못 집었다, D12 거부)
 *   found = true, inThisParcel = true  → 화면 매칭만 실패했다      (담기 허용)
 * </pre>
 * 뭉뚱그리면 작업자가 "등록해야 하는 물품"과 "잘못 집은 물품"을 구분하지 못한다.
 */
public record BarcodeLookupResponse(boolean found, Long productId, String productName,
                                    boolean inThisParcel) {

    public static BarcodeLookupResponse notRegistered() {
        return new BarcodeLookupResponse(false, null, null, false);
    }
}
