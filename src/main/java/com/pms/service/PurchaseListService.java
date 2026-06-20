package com.pms.service;

import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.response.PurchaseListResponse;

/**
 * "오늘 구매 목록"(사입 리스트) 비즈니스 로직.
 *
 * SSOT: oklyx-context/coupang-purchase-list-design.md (대안 B, 라인 단위 추적).
 * 구매(사입)만 다루며 입고(재고 증가)는 범위 밖. 주문 동기화는 order-sync 소관 — 여기선 적재된 order_item 만 사용.
 *
 * 모든 구매 목록 작업은 이 서비스를 경유해야 한다(Controller 에서 Repository 직접 호출 금지).
 *
 * @see com.pms.controller.PurchaseListController
 */
public interface PurchaseListService {

    /** ACCEPT 주문을 BOM 전개해 shopping_list_item.autoQty 를 멱등 재적재. manualQty/purchase_record 보존. */
    void extract(Long sellerId);

    /** 라인을 product 로 합산한 구매 목록(잔여>0) + 미매핑 주문. 저장 없음. */
    PurchaseListResponse getList(Long sellerId);

    /** 라인에 구매 기록 추가(부분구매/정정). */
    void addPurchase(Long itemId, PurchaseRecordRequest request);

    /** 수동 라인 추가 또는 기존 수동 라인에 manualQty 누적(product 당 1개 보장). */
    void addManual(ManualItemRequest request);

    /** 라인 manualQty 절대값 교체. */
    void adjustManual(Long itemId, ManualAdjustRequest request);
}
