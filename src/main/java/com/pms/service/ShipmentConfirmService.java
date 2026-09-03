package com.pms.service;

import com.pms.dto.request.ManualShipmentRequest;
import org.springframework.web.multipart.MultipartFile;

/**
 * 발송처리 레그: order_item 전개 → 쿠팡 송장업로드(상품준비중→배송지시) 전송.
 *
 * <p>경로는 <b>둘</b>이다:
 * <ul>
 *   <li>일괄 — 택배사 결과 xlsx 업로드({@link #confirm}). 택배사는 서버가 활성 1개를 자동 선택한다.</li>
 *   <li>단건 수동 — 주문 상세에서 라인 1건({@link #confirmManual}). 사용자가 택배사·송장번호를 지정하고,
 *       이미 배송지시 이상인 박스면 송장수정({@code updateInvoices}) 으로 보낸다(PLAN 2609_11 D3).</li>
 * </ul>
 *
 * 생성 레그({@link ShippingLabelService})의 후속. 생성은 접수시트 xlsx 다운로드, 이 서비스는 발송처리.
 * 조회는 읽기 전용. 단, 송장업로드에 성공한 박스의 {@code status} 만 {@code DEPARTURE} 로 갱신한다
 * (PLAN 2609_07 D4) — 그 외 필드·행은 동기화({@code OrderSyncFacade}) 전담.
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

    /**
     * 주문 라인 1건이 속한 <b>박스</b>에 대해 사용자가 고른 택배사·송장번호로 송장업로드/수정을 보낸다.
     * 같은 주문의 다른 박스는 건드리지 않는다(PLAN 2609_11 D1).
     *
     * @param request 앵커 라인 id · 택배사 id · 송장번호
     * @return 전송 결과(모드·성공/실패 상세·로컬 상태)
     * @throws IllegalArgumentException 라인 없음 · 비-COUPANG · externalBoxId 없음 · 택배사 코드 미등록 (→ 400)
     */
    ManualShipmentResult confirmManual(ManualShipmentRequest request);
}
