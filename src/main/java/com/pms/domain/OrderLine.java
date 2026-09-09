package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 라인 — oclyx 소유 중립 core (FEATURE_2609_26 / PLAN D2·D9·D10·D11).
 *
 * <p>플랫폼 자연키·상태 원문·raw 는 여기 없다 — {@link CoupangOrderLine} 이 소유한다(1:1).
 * 라인을 유일하게 만드는 키가 플랫폼마다 다르기 때문이다(쿠팡 4키 / 네이버 productOrderId 1키).
 *
 * <p>🔴 <b>금액 4컬럼은 주문 시점 스냅샷</b>이다 — 최초 적재에서만 쓰고 이후 동기화가 덮지 않는다(D10).
 * 취소는 수량({@code cancelQty})으로만 반영된다. 컬럼명에 쿠팡 필드명({@code salesPrice}/{@code orderPrice})을
 * 쓰지 않는다(D9) — 여기서 쿠팡 이름을 쓰면 이번에 걷어낸 실수를 그대로 반복한다.
 *
 * <p>실매출 = {@code lineAmount − discountAmount}({@code lineAmount} 는 <b>할인 전</b> 금액이다).
 * 이 계산은 저장 시점에 굽지 않고 정산·통계가 한다.
 *
 * <p>⚠️ {@code status} 에 {@code CANCELLED} 는 저장되지 않는다 — {@link #effectiveStatus()} 가 파생한다.
 *
 * <p>⚠️ ddl-auto=validate(운영) → 아래 @Column 정의는 실제 order_line DDL(changeset 066)과 일치해야 한다.
 */
@Entity
@Table(name = "order_line")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OrderLine extends BaseEntity {

    /**
     * 🔴 백필이 기존 {@code order_item.id} 를 그대로 옮겨 심는다(shared-id, changeset 009 트릭).
     * 덕분에 {@code order_claim}·{@code customer_inquiry}·{@code shopping_list_item}·
     * {@code order_cancel_action} 의 FK 를 값 변환 없이 컬럼 rename 만으로 재배선할 수 있다.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (PLAN D25). Hibernate auto-sets this on INSERT and auto-filters SELECTs.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 배송 묶음. 플랫폼이 묶음 식별자를 주지 않으면 null 이다(배송비를 담을 자리가 없다). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_shipment_id")
    private OrderShipment orderShipment;

    /**
     * 이 라인이 팔린 채널 옵션 — 중립 링크(FEATURE_2609_28 / PLAN D15, changeset 079).
     *
     * <p>🔴 <b>방향이 여기서 뒤집힌다.</b> 이전에는 옵션 매칭키를 {@link CoupangOrderLine#getVendorItemId()}
     * 에서 읽었다 — 중립 객체가 플랫폼 거울 행을 알아야 하는 구조였고, 거울 행이 없는 플랫폼(네이버) 주문은
     * BOM 전개에서 조용히 빠졌다. 구매목록에서 행이 안 보이는 정도일 때는 견딜 수 있었지만
     * <b>재고 차감이 이 전개에 얹히는 순간</b>부터는 견딜 수 없다.
     *
     * <p>⚠️ nullable 이다 — 컬럼 이전 라인과 옵션 매칭에 실패한 라인은 null 로 남고, 소비자는 거울 행
     * 폴백({@code OrderLineExpander})으로 내려간다. NOT NULL 로 만들면 주문 동기화가 막힌다.
     * ⚠️ 적재는 <b>비어 있을 때만 채운다</b> — 재동기화가 값을 덮으면 WING 수정으로 깨진 매칭이
     * 멀쩡한 값을 밀어낸다([[project_wing_edit_desync]]).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_option_id")
    private ProductListingOption productListingOption;

    /**
     * 정규화 상태. 매핑 실패(모르는 플랫폼 상태)는 적재 자체를 스킵하므로 신규 행은 항상 값이 있다 —
     * nullable 인 것은 백필이 매핑하지 못한 과거 값을 <b>숨기지 않고 드러내기</b> 위해서다(PLAN D7).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30)
    private OrderStatus status;

    @Column(name = "item_name", length = 500)
    private String itemName;

    @Column(name = "order_qty", nullable = false)
    private Integer orderQty;              // 쿠팡 shippingCount

    @Column(name = "cancel_qty", nullable = false)
    private Integer cancelQty;             // 취소확정 수량 (기본 0)

    @Column(name = "hold_qty", nullable = false)
    private Integer holdQty;               // holdCountForCancel = 환불대기 수량 (기본 0)

    /** 단가(쿠팡 salesPrice). 최초 적재 스냅샷 — 이후 동기화가 덮지 않는다. */
    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** 라인 합계(쿠팡 orderPrice) = 단가 × 수량, <b>할인 전</b>. */
    @Column(name = "line_amount", precision = 12, scale = 2)
    private BigDecimal lineAmount;

    /** 총 할인(쿠팡 discountPrice). */
    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    /**
     * 플랫폼 부담 할인(쿠팡 coupangDiscount).
     *
     * <p>🔴 {@code discountAmount − platformDiscountAmount} 를 "판매자 부담"이라 부르지 말 것 —
     * 정확한 의미는 <b>"플랫폼 부담이 아닌 할인"</b> 이고, 부담 주체는 정산 API 가 확정한다.
     */
    @Column(name = "platform_discount_amount", precision = 12, scale = 2)
    private BigDecimal platformDiscountAmount;

    /**
     * 원가 스냅샷 3컬럼 — 물건이 <b>실제로 나갈 때</b> 굽는다 (FEATURE_2609_28 / PLAN D20, changeset 081).
     *
     * <p>🔴 리포트 시점에 {@code Product.price} 나 매입 이력을 조인해 과거 손익을 계산하지 않는다.
     * 6월 매입가를 9월에 고치면 6월 리포트 숫자가 달라지고, 그러면 아무도 리포트를 믿지 않는다.
     *
     * <p>⚠️ 전부 nullable 이다. 값이 비었다는 것은 <b>아직 안 나갔다</b>는 뜻이고 그것이 정상이다.
     * 첫 {@code STOCK_OUT} 에서만 굽고 두 번째 출고에서 <b>덮지 않는다</b> — 부분 출고마다 다시 구우면
     * 같은 라인의 원가가 출고 횟수만큼 흔들린다. 출고 뒤 취소·반품에도 되돌리지 않는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_basis", length = 20)
    private CostBasis costBasis;

    /** Σ(구성 물품 단가 × 소진 수량). 단가를 하나도 못 구하면 <b>0 이 아니라 null</b> — 모르는 것은 모르는 채로 둔다. */
    @Column(name = "cost_amount", precision = 15, scale = 4)
    private BigDecimal costAmount;

    /** 스냅샷을 구운 시각. 구성비 집계({@code CostBasisBreakdown})의 기간 축이다. */
    @Column(name = "cost_snapshot_at")
    private LocalDateTime costSnapshotAt;

    /** 발주가능수량 = orderQty − (cancelQty + holdQty), 음수면 0. */
    public int purchasableQty() {
        return Math.max(0, orderQty - (cancelQty + holdQty));
    }

    /**
     * 전량 취소 여부 = 확정취소+환불대기(cancelQty+holdQty)가 주문수량 이상.
     *
     * <p>판매자 취소는 플랫폼 상태를 바꾸지 않고 취소수량만 반영되므로(반품 동기화는 상태 미변경, 또
     * 전량취소된 주문은 INSTRUCT 목록에서 빠져 상태가 얼어붙는다), 취소수량으로 취소를 판정한다.
     */
    public boolean isFullyCancelled() {
        return orderQty != null && orderQty > 0 && (cancelQty + holdQty) >= orderQty;
    }

    /**
     * 표시·필터용 유효 상태 — 전량 취소면 {@link OrderStatus#CANCELLED}, 아니면 저장된 상태.
     *
     * <p>🔴 전량취소 판정은 <b>서버가 소유한다</b>(PLAN D26) — 클라이언트가 수량으로 다시 판정하면
     * hold 포함 여부가 갈려 "배지는 취소인데 필터엔 안 뜨는" 불일치가 생긴다.
     */
    public OrderStatus effectiveStatus() {
        return isFullyCancelled() ? OrderStatus.CANCELLED : status;
    }
}
