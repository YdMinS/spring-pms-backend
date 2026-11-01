package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A cash settlement between two sellers — 「돈으로 정리」 (FEATURE_2610_19 D39 · D47 · D90 · D99 ①).
 *
 * <p>oclyx cannot verify it (D90): the amount is not checked against the pair balance, so an amount larger than the
 * balance simply turns the balance around. Only the lower limit (1 won, D99 ①) is checked.
 *
 * <p>A wrong entry is cancelled, not deleted (D90): {@code cancelledAt} set = left out of the balance, still listed.
 */
@Entity
@Table(name = "seller_settlement")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SellerSettlement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** The seller who paid the money. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_seller_id", nullable = false)
    private Seller payer;

    /** The other seller of the pair, who received it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_seller_id", nullable = false)
    private Seller receiver;

    @Column(name = "settled_on", nullable = false)
    private LocalDate settledOn;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 500)
    private String memo;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}
