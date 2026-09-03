package com.pms.service;

import java.util.List;

/**
 * 플랫폼별 택배사 코드 조회의 <b>유일 진입점</b>.
 *
 * 발송처리(송장업로드) 레그가 `계정.platform → deliveryCompanyCode` 를 얻기 위해 이 서비스를 사용한다.
 * ❌ {@code CarrierRepository}/{@code PlatformCarrierCodeRepository} 를 Controller/다른 Service 에서
 *    직접 호출하지 말고 반드시 이 서비스를 경유할 것.
 *
 * <p>코드 해석 경로는 <b>둘</b>이고 용도가 다르다:
 * <ul>
 *   <li>일괄(xlsx) 발송처리 — {@link #resolveDeliveryCompanyCode(String)}: 활성 택배사 1개를 서버가 자동 선택.</li>
 *   <li>단건 수동 발송처리 — {@link #findOptions(String)} 로 고른 것을
 *       {@link #resolveDeliveryCompanyCode(Long, String)} 로 해석(사용자 선택).</li>
 * </ul>
 *
 * @see com.pms.service.ShippingLabelService 발송처리 레그(이 메서드로 코드를 얻는다)
 */
public interface CarrierCodeService {

    /**
     * 활성 택배사의, 해당 플랫폼에서의 deliveryCompanyCode 를 반환.
     *
     * @param platform 플랫폼 코드(예: "COUPANG") — 보통 {@code MarketplaceAccount.getPlatform()}
     * @return 해당 플랫폼용 택배사 코드(예: 쿠팡 "CJGLS")
     * @throws IllegalStateException 활성 택배사가 없거나, 해당 플랫폼 코드가 미설정인 경우
     */
    String resolveDeliveryCompanyCode(String platform);

    /** 그 플랫폼에 코드가 등록된 활성 택배사 목록(없으면 빈 리스트 — 예외 아님, PLAN 2609_11 D16). */
    List<CarrierOption> findOptions(String platform);

    /**
     * 사용자가 고른 택배사의, 해당 플랫폼 코드를 반환(단건 발송처리 전용).
     *
     * <p>⚠️ 활성 여부를 다시 확인하지 않는다 — 목록이 활성만 주고, 등록된 코드로 보내는 것 자체는 유효하다.
     * 코드가 없으면 {@link IllegalArgumentException}(400) 이며,
     * {@link #resolveDeliveryCompanyCode(String)} 의 {@link IllegalStateException}(설정 오류=500)과 의미가 다르다.
     *
     * @throws IllegalArgumentException 그 (carrierId, platform) 코드가 없을 때 → 400
     */
    String resolveDeliveryCompanyCode(Long carrierId, String platform);
}
