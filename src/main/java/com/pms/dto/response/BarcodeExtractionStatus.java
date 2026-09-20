package com.pms.dto.response;

/**
 * 물품 하나의 바코드 추출 결과 (FEATURE_2609_65 / PLAN D12).
 *
 * <p>상품 사진은 보통 정면 연출컷이라 <b>대부분 실패한다</b>. 그래서 실패를 뭉뚱그리지 않고
 * 원인별로 돌려준다 — 화면이 이 값을 그대로 문장으로 보여준다.</p>
 */
public enum BarcodeExtractionStatus {

    /** 읽어서 저장했다. */
    EXTRACTED,

    /** 사진은 읽었는데 바코드가 없다 — 정상적인 실패다. */
    NOT_FOUND,

    /** 볼 사진이 없다(갤러리도 레거시 대표 이미지도 없음). */
    NO_IMAGE,

    /** 사진을 가져오지 못했다(네트워크·깨진 파일) — 사람이 볼 일이다. */
    READ_FAILED,

    /** 읽었지만 다른 물품이 쓰는 값이라 저장하지 않았다. */
    DUPLICATE,

    /** 이미 바코드가 있어 손대지 않았다(overwrite=false). */
    SKIPPED_EXISTING
}
