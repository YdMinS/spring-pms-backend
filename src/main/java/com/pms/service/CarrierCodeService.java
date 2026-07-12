package com.pms.service;

/**
 * 플랫폼별 택배사 코드 조회의 <b>유일 진입점</b>.
 *
 * 발송처리(송장업로드) 레그가 `계정.platform → deliveryCompanyCode` 를 얻기 위해 이 서비스를 사용한다.
 * ❌ {@code CarrierRepository}/{@code PlatformCarrierCodeRepository} 를 Controller/다른 Service 에서
 *    직접 호출하지 말고 반드시 이 서비스를 경유할 것.
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
}
