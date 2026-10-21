package com.pms.domain;

import com.pms.domain.converter.MapStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.Map;

/**
 * ESM (Gmarket · Auction) shipping settings of one sales channel = the channel layer (FEATURE_2610_26 / D36), next to
 * {@link CoupangShippingConfig} and {@link ElevenstShippingConfig}. The values are one map whose keys are the
 * {@code esm.*} keys of {@link com.pms.service.listing.shipping.EsmShippingKeys} — the same keys a listing shipping
 * override uses (there is no master layer, D36).
 *
 * <p>⚠️ {@code marketplaceAccount} is UNIQUE (1:1) — update the existing row with {@code toBuilder()}.</p>
 */
@Entity
@Table(name = "esm_shipping_config",
        uniqueConstraints = @UniqueConstraint(name = "uq_esm_shipcfg_account",
                columnNames = "marketplace_account_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class EsmShippingConfig extends BaseEntity {

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

    /** {@code esm.*} key → value (all strings). null = nothing set yet. */
    @Convert(converter = MapStringConverter.class)
    @Column(name = "settings", columnDefinition = "TEXT")
    private Map<String, String> settings;
}
