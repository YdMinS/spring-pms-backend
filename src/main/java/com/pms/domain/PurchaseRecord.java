package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 구매 이력 (shopping_list_item 라인에 묶임).
 *
 * 라인 구매수량 = SUM(quantity), 라인 잔여 = 라인 필요수량 − 구매수량.
 * 부분구매: 필요 8 에 5만 사면 quantity=5 행 → 잔여 3 계속 노출. 오입력 정정은 음수 행 추가(quantity 음수 허용).
 * 라인에 묶여 있어 그 주문이 출고/취소돼도 잔여가 깨지지 않는다.
 *
 * ⚠️ ddl-auto=validate(운영) → 아래 @Column 정의는 실제 purchase_record DDL 과 일치해야 한다.
 *
 * @see com.pms.domain.ShoppingListItem 구매 필요 라인
 */
@Entity
@Table(name = "purchase_record")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PurchaseRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shopping_list_item_id", nullable = false)
    private ShoppingListItem item;

    /** 구매 날짜 (통계용). */
    @Column(name = "purchased_on", nullable = false)
    private LocalDate purchasedOn;

    /** 그날 구매 수량. 정정 시 음수 허용. */
    @Column(nullable = false)
    private Integer quantity;
}
