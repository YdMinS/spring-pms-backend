package com.pms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 쿠팡 OpenAPI 연동 설정 (application.yml 의 oklyx.coupang.* 바인딩).
 *
 * {vendorId} 토큰은 호출 시 계정의 vendorId 로 치환된다.
 */
@Component
@ConfigurationProperties(prefix = "oklyx.coupang")
@Getter
@Setter
public class CoupangProperties {

    /** ordersheets(발주서 목록) 조회 경로. {vendorId} 치환. */
    private String ordersheetsPath = "/v2/providers/openapi/apis/api/v5/vendors/{vendorId}/ordersheets";

    /** ordersheets 조회 기간(일). createdAtFrom = 오늘 − syncDays. 쿠팡 최대 31일. */
    private int syncDays = 14;

    /**
     * INSTRUCT(상품준비중) 조회 윈도우(일). 송장 접수시트 생성 전용.
     * createdAtFrom = 오늘 − instructDays. INSTRUCT 는 결제 후 며칠 정체 가능하므로 syncDays 와 분리한다.
     * 쿠팡 최대 31일.
     */
    private int instructDays = 14;

    /** returnRequests(반품/취소 요청 목록) 조회 경로. {vendorId} 치환. */
    private String returnrequestsPath = "/v2/providers/openapi/apis/api/v6/vendors/{vendorId}/returnRequests";

    /** 취소 보정 조회 기간(일). 취소는 늦게 처리되므로 ordersheets 보다 넉넉히. 쿠팡 최대 31일. */
    private int cancelSyncDays = 7;
}
