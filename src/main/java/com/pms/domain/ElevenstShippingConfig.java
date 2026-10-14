package com.pms.domain;

import com.pms.domain.converter.MapStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.Map;

/**
 * 11st shipping settings of one sales channel = the channel default (FEATURE_2610_13 / D84), next to
 * {@link CoupangShippingConfig}. The values are one map whose keys are the {@code elevenst.*} keys of
 * {@link com.pms.service.listing.shipping.ElevenstShippingKeys} — the same keys a master / listing shipping
 * override uses (D104), so the three layers resolve key by key.
 *
 * <p>⚠️ {@code marketplaceAccount} is UNIQUE (1:1) — update the existing row with {@code toBuilder()}.</p>
 */
@Entity
@Table(name = "elevenst_shipping_config",
        uniqueConstraints = @UniqueConstraint(name = "uq_elevenst_shipcfg_account",
                columnNames = "marketplace_account_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ElevenstShippingConfig extends BaseEntity {

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

    /** {@code elevenst.*} key → value (all strings). null = nothing set yet. */
    @Convert(converter = MapStringConverter.class)
    @Column(name = "settings", columnDefinition = "TEXT")
    private Map<String, String> settings;
}
