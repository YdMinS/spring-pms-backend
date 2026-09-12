package com.pms.service;

import com.pms.domain.Platform;

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
 *   <li>일괄(xlsx) 발송처리 — {@link #resolveDeliveryCompanyCode(Platform)}: 활성 택배사 1개를 서버가 자동 선택.</li>
 *   <li>단건 수동 발송처리 — {@link #findOptions(String)} 로 고른 코드를
 *       {@link #validateDeliveryCompanyCode(String, String)} 로 검증(사용자 선택).</li>
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
    String resolveDeliveryCompanyCode(Platform platform);

    /**
     * 단건 발송처리 드롭다운용 택배사 목록.
     *
     * <p>코드표는 {@link com.pms.domain.CarrierCatalog}(플랫폼 × 코드 × 이름, PLAN 2609_37 D1)에서
     * 온다 — 그 플랫폼의 카탈로그 전량을 {@code display_order} 순으로 주고, 택배사 관리에 등록해 둔
     * 코드는 {@code registered=true} 로 맨 위에 온다. 카탈로그가 빈 플랫폼(아직 시드하지 않은 마켓)은
     * 등록된 활성 택배사만 준다 (없으면 빈 리스트 — 예외 아님, 2609_11 D16 · 2609_37 D9).
     */
    List<CarrierOption> findOptions(Platform platform);

    /**
     * 사용자가 고른 택배사 코드를 검증하고 그대로 반환(단건 발송처리 전용).
     *
     * <p>드롭다운이 코드를 그대로 돌려주므로 해석할 것은 없고, <b>화이트리스트 검증</b>이 일이다 —
     * 그 플랫폼의 {@link com.pms.domain.CarrierCatalog} 행이 있으면 카탈로그에 있는 코드만,
     * 카탈로그가 빈 플랫폼은 택배사 관리에 등록된 코드만 통과한다(PLAN 2609_37 D9).
     * 없는 코드면 {@link IllegalArgumentException}(400) 이며,
     * {@link #resolveDeliveryCompanyCode(Platform)} 의 {@link IllegalStateException}(설정 오류=500)과 의미가 다르다.
     *
     * @throws IllegalArgumentException 그 플랫폼에서 쓸 수 없는 코드일 때 → 400
     */
    String validateDeliveryCompanyCode(String deliveryCompanyCode, Platform platform);
}
