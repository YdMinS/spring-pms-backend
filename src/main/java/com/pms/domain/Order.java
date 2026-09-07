package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * 주문 헤더 — oclyx 소유 중립 core (FEATURE_2609_26 / PLAN D2·D11).
 *
 * <p>주문 3층(주문 → 배송묶음 → 라인)의 최상위다. 쿠팡 3층과 네이버 2층(주문 → 상품주문)의 공통분모라
 * 플랫폼 고유 식별자·상태 원문은 여기 두지 않고 {@link CoupangOrderLine} 같은 extension 이 소유한다.
 *
 * <p>⚠️ 테이블명이 {@code orders} 인 이유: {@code order} 는 SQL 예약어다.
 * ⚠️ 클래스명 {@code Order} 는 {@code jakarta.persistence.criteria.Order}·
 * {@code org.springframework.data.domain.Sort.Order} 와 import 충돌이 잦다 — 정렬을 쓰는 파일에서는
 * FQN 이나 import 정리를 먼저 확인할 것.
 *
 * <p>고객 <b>이름</b>(주문자·수취인)만 저장한다 — 연락처·주소·배송메시지는 여전히 미저장(개인정보 최소화).
 *
 * <p>⚠️ ddl-auto=validate(운영) → 아래 @Column 정의는 실제 orders DDL(changeset 066)과 일치해야 한다.
 */
@Entity
@Table(name = "orders",
        uniqueConstraints = @UniqueConstraint(name = "uq_orders_account_order",
                columnNames = {"marketplace_account_id", "external_order_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (PLAN D25). Hibernate auto-sets this on INSERT and auto-filters SELECTs —
    // do NOT set it in the builder and do NOT add manual tenant conditions to queries.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Platform platform;

    /** 플랫폼 주문번호(쿠팡 orderId · 네이버 orderId). 계정 안에서 유일하다. */
    @Column(name = "external_order_id", nullable = false, length = 100)
    private String externalOrderId;

    /** 결제 시각(쿠팡 paidAt) — KST 로컬시각. 조회 창·정렬의 기준이다. */
    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Column(name = "orderer_name", length = 100)
    private String ordererName;

    @Column(name = "receiver_name", length = 100)
    private String receiverName;
}
