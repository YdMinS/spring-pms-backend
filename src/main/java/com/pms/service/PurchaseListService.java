package com.pms.service;

import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.dto.response.PurchaseProductGroup;
import com.pms.dto.response.PurchaseRecordResult;
import com.pms.dto.response.PurchaseRecordView;

import java.time.LocalDate;
import java.util.List;

/**
 * "오늘 구매 목록"(사입 리스트) 비즈니스 로직.
 *
 * <p>🔴 판매자 스코프가 없다(PLAN 2609_29 D11·D13). 툴바 드롭다운이 "보기 범위"와 "동기화 범위" 두 뜻을
 * 겸하면서 리셋 범위와 재적재 범위가 어긋나는 결함을 만들었다 — 조회·추출 모두 항상 전체다.
 * 판매자는 조회 축이 아니라 <b>입고 시 귀속</b>으로만 존재한다(D0).
 *
 * <p>🔴 입고는 하나의 이벤트다(D1): {@link #addPurchase}가 purchase_record(돈)와 stock_movement(실물)를
 * <b>같은 트랜잭션</b>에서 쓴다. 따로 입력하게 하면 둘 중 하나가 반드시 빈다.
 *
 * 주문 동기화는 order-sync 소관 — 여기선 적재된 order_line 만 사용한다.
 * 모든 구매 목록 작업은 이 서비스를 경유해야 한다(Controller 에서 Repository 직접 호출 금지).
 *
 * @see com.pms.controller.PurchaseListController
 */
public interface PurchaseListService {

    /**
     * 결제완료 주문을 BOM 전개해 shopping_list_item.autoQty 를 멱등 재적재. manualQty/purchase_record 보존.
     * 리셋도 재적재도 <b>전체</b>라 두 범위가 항상 일치한다(D11).
     */
    void extract();

    /** 라인을 product 로 합산한 구매 목록(잔여>0) + 미매핑 주문. 저장 없음. */
    PurchaseListResponse getList();

    /**
     * 구매 완료 목록(잔여<=0 && 구매>0)을 product 로 합산. 저장 없음.
     * from/to: 구매일 기준 기간 필터(경계 포함). null=무제한.
     *
     * <p>응답 타입은 구매목록과 동일하다(D21) — 완료탭도 같은 토글(입고 카드·채널 칩·주문 줄)을 그린다.
     */
    List<PurchaseProductGroup> getCompletedList(LocalDate from, LocalDate to);

    /**
     * 입고 1회 — 구매기록 저장 + (조건부) 재고 입고를 같은 트랜잭션에서 (D1·D17·D19).
     * 음수 정정이거나 {@code recordStock=false} 면 재고는 기록하지 않고 결과로 알린다.
     */
    PurchaseRecordResult addPurchase(PurchaseRecordRequest request);

    /** 그 물품의 최근 구매이력 — <b>판매자 무관</b>, 최신순 limit 건 (D9). */
    List<PurchaseRecordView> recentPurchases(Long productId, int limit);

    /** 수동 라인 추가 또는 기존 수동 라인에 manualQty 누적(product 당 1개 보장). */
    void addManual(ManualItemRequest request);

    /** 라인 manualQty 절대값 교체. */
    void adjustManual(Long itemId, ManualAdjustRequest request);
}
