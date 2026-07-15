package com.pms.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 발송처리 레그: 택배사 결과 xlsx → order_item 전개 → 쿠팡 송장업로드(상품준비중→배송지시) 배치 전송.
 *
 * 생성 레그({@link ShippingLabelService})의 후속. 생성은 접수시트 xlsx 다운로드, 이 서비스는 결과 업로드→발송처리.
 * ❌ order_item 에 쓰지 않는다(읽기 전용). 동기화는 {@code OrderSyncFacade} 담당.
 * ⚠️ 현재 대상 플랫폼은 COUPANG 뿐 — 네이버 등은 후속 어댑터(현재 스코프 아님).
 */
public interface ShipmentConfirmService {

    /**
     * 택배사 결과 xlsx 를 파싱해 계정별로 쿠팡 송장업로드 API 를 배치 전송하고 결과를 집계한다.
     *
     * @param file 택배사 고정 양식 xlsx (주문번호 col 5, 운송장번호 col 6 만 사용)
     * @return 전개/그룹핑/응답집계 결과
     * @throws IllegalArgumentException 빈 파일/파싱 실패(→ 400)
     */
    ShipmentConfirmResult confirm(MultipartFile file);
}
