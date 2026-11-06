package com.pms.domain;

import com.pms.domain.converter.MapStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.Map;

/**
 * LotteON shipping settings of one sales channel = the channel layer (FEATURE_2610_24 / D42 · D65), next to
 * {@link CoupangShippingConfig}, {@link ElevenstShippingConfig} and {@link EsmShippingConfig}. The values are one map
 * whose keys are the {@code lotteon.*} keys of {@link com.pms.service.listing.shipping.LotteonShippingKeys} — the same
 * keys a listing shipping override uses (there is no master layer, D42).
 *
 * <p>⚠️ {@code marketplaceAccount} is UNIQUE (1:1) — update the existing row with {@code toBuilder()}.</p>
 */
@Entity
@Table(name = "lotteon_shipping_config",
        uniqueConstraints = @UniqueConstraint(name = "uq_lotteon_shipcfg_account",
                columnNames = "marketplace_account_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LotteonShippingConfig extends BaseEntity {

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

    /** {@code lotteon.*} key → value (all strings). null = nothing set yet. */
    @Convert(converter = MapStringConverter.class)
    @Column(name = "settings", columnDefinition = "TEXT")
    private Map<String, String> settings;
}
