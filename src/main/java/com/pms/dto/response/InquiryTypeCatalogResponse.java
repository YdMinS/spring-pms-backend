package com.pms.dto.response;

import com.pms.domain.InquiryType;

import java.util.List;

/**
 * 플랫폼이 지원하는 문의 유형 — GET /api/inquiries/types (FEATURE_2609_23 / D4).
 *
 * 유형표의 원천은 <b>서버 상수 1곳</b>({@code InquiryTypeCatalog})이다. 프론트에 같은 표를 만들지 않는다 —
 * 프론트가 {@code if (platform === 'COUPANG')} 로 탭을 그리기 시작하면 플랫폼을 붙일 때 화면을 다시 짠다.
 */
public record InquiryTypeCatalogResponse(String platform, List<Option> types) {

    /** @param code 문의 유형 enum 값 그대로 / @param label 사용자 노출 문구(서버가 정한다) */
    public record Option(InquiryType code, String label) {
    }
}
