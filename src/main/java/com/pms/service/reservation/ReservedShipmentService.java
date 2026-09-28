package com.pms.service.reservation;

import com.pms.dto.response.ReservationCreateResult;
import com.pms.dto.response.ReservedShipmentRowResponse;
import com.pms.dto.response.StoredInvoiceResponse;
import com.pms.service.ShipmentConfirmResult;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/** 예약 발송 관리 창구 (FEATURE_2609_75 / E5~E9 · E12~E16). 화면 단위는 주문(결과 행), 송장 단위는 배송 묶음이다(D30 · D18). */
public interface ReservedShipmentService {

    /** E5 — 결과 파일 + 실행 시각(KST) → 예약 1건(D20·D27). 파일은 저장하지 않는다. */
    ReservationCreateResult create(MultipartFile file, LocalDateTime executeAt);

    /** E6 — 주문 행: 끝나지 않은 것 전부 + 끝난 것 최근 7일(KST)(D30). */
    List<ReservedShipmentRowResponse> list();

    /** E13 — 주문 1건의 예약 발송 기록 전부(주문 상세, D30). 보관 행(STORED)은 빼고. */
    List<ReservedShipmentRowResponse> listByOrder(String externalOrderId);

    /** E7 — 선택 라인의 발송대기중 묶음 예약 취소 → 「내부 상품준비중」, 송장은 STORED 로 남는다(D18 행2). */
    InternalStageResult cancelItems(List<Long> orderItemIds);

    /** E8 — 그 주문의 실행 시각 변경(D18 행3). 같은 예약의 다른 주문은 그대로다(D30). */
    ReservedShipmentRowResponse changeTime(Long itemId, LocalDateTime executeAt);

    /** E9 — 자동 재시도가 멈춘 그 주문을 지금 다시 실행(D16 [다시 시도]). */
    ReservedShipmentRowResponse retry(Long itemId);

    /** E14 — 그 주문의 내부 단계 배송 묶음별 현재 송장(D18). 송장이 없는 묶음은 택배사·송장 null. */
    List<StoredInvoiceResponse> invoicesOfOrder(String externalOrderId);

    /** E12 — 배송 묶음 1개의 택배사·송장번호 저장(D18 🔁). 내부 단계 + (PAID 라인 OR 열린 예약 결과) 동안 언제나 — 실행 중(RUNNING)만 거절. */
    StoredInvoiceResponse changeInvoice(Long orderShipmentId, String deliveryCompanyCode, String invoiceNumber);

    /** E15 — 선택 라인의 「내부 상품준비중」 묶음을 저장된 송장으로 예약(D18 · D20). 파일 없음. */
    ReservationCreateResult reserveStored(List<Long> orderItemIds, LocalDateTime executeAt);

    /** E16 — 선택 라인의 내부 단계 묶음을 저장된 송장으로 지금 발주처리 → 송장 등록(D18 · D27). */
    ShipmentConfirmResult shipStoredNow(List<Long> orderItemIds);
}
