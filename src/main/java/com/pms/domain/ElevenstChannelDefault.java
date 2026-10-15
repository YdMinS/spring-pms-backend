package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * The 11st notice defaults of one sales channel (FEATURE_2610_16 / D4 · D6), next to
 * {@link ElevenstShippingConfig}. The 11st payload falls back to these two texts when the listing and the master
 * leave a notice blank (D5).
 *
 * <p>⚠️ {@code marketplaceAccount} is UNIQUE (1:1) — update the existing row with {@code toBuilder()}.
 * Both texts are nullable: null = not entered on this channel (D16).</p>
 */
@Entity
@Table(name = "elevenst_channel_default",
        uniqueConstraints = @UniqueConstraint(name = "uq_elevenst_chdef_account",
                columnNames = "marketplace_account_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ElevenstChannelDefault extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Hibernate auto-sets this on INSERT and auto-filters SELECTs — do NOT set it in the builder.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    /** A/S notice sent as 11st {@code asDetail}. */
    @Column(name = "as_notice", columnDefinition = "TEXT")
    private String asNotice;

    /** Return/exchange notice sent as 11st {@code rtngExchDetail}. */
    @Column(name = "return_exchange_notice", columnDefinition = "TEXT")
    private String returnExchangeNotice;
}
