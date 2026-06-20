package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 구매 필요 라인 (order_item × 구성품). "오늘 구매 목록"의 SSOT 라인.
 *
 * 한 주문 라인(옵션)을 BOM 전개하면 구성품마다 1행이 생긴다. order_item 이 NULL 이면 수동 추가 라인이다.
 * 라인 필요수량 = autoQty + manualQty. 화면은 product 로 합산해 보여준다.
 *
 * 추출(extract)은 멱등 upsert 다: 재추출 시 autoQty 만 갱신하고 manualQty / 연결된 purchase_record 는 보존한다.
 * 취소·환불대기·출고로 발주가능수량이 줄면 재추출에서 해당 라인 autoQty 가 자동으로 0 이 되어 목록에서 빠진다.
 *
 * ⚠️ ddl-auto=validate(운영) → 아래 @Column 정의는 실제 shopping_list_item DDL 과 일치해야 한다.
 *    UNIQUE(order_item_id, product_id). 수동 라인(order_item_id NULL) 의 "product 당 1개" 는 서비스 계층이 보장한다.
 *
 * @see com.pms.domain.PurchaseRecord 라인에 묶인 구매 이력
 * @see com.pms.domain.OrderItem 주문 라인(쿠팡 거울)
 */
@Entity
@Table(name = "shopping_list_item",
        uniqueConstraints = @UniqueConstraint(name = "uq_shopping_list_item",
                columnNames = {"order_item_id", "product_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ShoppingListItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 추출 출처 주문 라인. NULL = 수동 추가 라인. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;

    /** 실제 사입 단위(구성품). 집계/표시 기준. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** 추출 자동값 = 발주가능수량 × 옵션당 구성수량. 수동 라인은 0. */
    @Column(name = "auto_qty", nullable = false)
    private Integer autoQty;

    /** 수동 추가/조정분. 재추출에도 보존. 기본 0. */
    @Column(name = "manual_qty", nullable = false)
    private Integer manualQty;

    /** 라인 필요수량 = autoQty + manualQty. */
    public int neededQty() {
        return autoQty + manualQty;
    }
}
