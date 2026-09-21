package com.pms.service.listing;

import java.util.List;

/**
 * 마켓 상품 이름 검색 결과 한 페이지(2609_67). 읽기 전용 · 저장 0회.
 *
 * <p>결과 없음은 <b>빈 목록</b>이지 예외가 아니다 — 참고 패널은 "그런 이름이 없다"도 정상 결과로 보여준다.</p>
 *
 * @param items     이 페이지의 후보 목록, 마켓 응답 순서 그대로. never null
 * @param nextToken 이어보기 키 — <b>없으면 null</b>(프론트의 [더 보기] 판정 기준). 빈 문자열을 내리지 말 것
 */
public record ChannelProductPage(List<ChannelProductSummary> items, String nextToken) {

    /** "없음"은 null 이 아니라 빈 List 다 — 호출부가 null 검사를 하지 않도록. */
    public ChannelProductPage {
        items = items == null ? List.of() : items;
    }
}
