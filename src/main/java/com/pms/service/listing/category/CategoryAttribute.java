package com.pms.service.listing.category;

import java.util.List;

/**
 * One normalized required/optional category attribute (FEATURE_2608_06 / 47). Platform-agnostic shape so the
 * NAVER adapter can reuse it later.
 *
 * @param name      attribute display name (Coupang {@code attributeTypeName})
 * @param required  true when the platform marks it MANDATORY
 * @param inputType {@code TEXT} / {@code SELECT} / {@code NUMBER} (adapter-normalized)
 * @param options   SELECT candidates (empty when not a SELECT input)
 * @param basicUnit 기본 단위(쿠팡 {@code basicUnit}); 단위 없음({@code "없음"})·공백은 null
 * @param groupNumber 택1 그룹 번호(쿠팡 {@code groupNumber}). 같은 번호를 가진 MANDATORY 속성끼리는
 *                    <b>그룹 중 하나만 채우면 충족</b>이다 — 실측(72882)에서 {@code 최소 중량}·{@code 최소 용량}
 *                    이 둘 다 MANDATORY + {@code groupNumber:"1"} 이고, 고체/액체 상품은 둘 중 하나만 기재한다.
 *                    그룹 없음은 쿠팡이 리터럴 {@code "NONE"} 으로 주며 여기서는 null 로 정규화한다.
 */
public record CategoryAttribute(String name, boolean required, String inputType, List<String> options,
                                String basicUnit, String groupNumber) {

    /** 그룹 정보가 없는 호출부(테스트·레거시)를 위한 편의 생성자 — {@code groupNumber = null}. */
    public CategoryAttribute(String name, boolean required, String inputType, List<String> options,
                             String basicUnit) {
        this(name, required, inputType, options, basicUnit, null);
    }

    /** 택1 그룹에 속하는가(그룹 번호가 있고 {@code "NONE"} 이 아님). */
    public boolean grouped() {
        return groupNumber != null && !groupNumber.isBlank();
    }
}
