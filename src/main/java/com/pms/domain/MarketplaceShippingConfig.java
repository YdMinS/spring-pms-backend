package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Shipping configuration for one marketplace account (FEATURE_2608_06 / 72) — the outbound place, the return
 * center (full address block) and the delivery settings needed for Coupang product registration.
 *
 * <p>Platform-neutral: the values may come from a platform lookup ({@code ShippingPlaceProvider}) or from manual
 * entry — this entity does not distinguish. One config per account (delivery settings are per-account; a
 * per-listing override is out of scope), enforced by the UNIQUE {@code marketplace_account_id}
 * ({@code @OneToOne}).</p>
 *
 * <p>All fields are nullable (partial save allowed while the wizard fills in); register (73) guards any missing
 * required value with a 400. {@code remoteAreaDeliverable} is a "Y"/"N" String (not Boolean) to sidestep the
 * MySQL BOOLEAN↔BIT trap (changeset 006), and register transmits the same "Y"/"N".</p>
 *
 * <p>⚠️ No {@code @TenantId} — isolation flows through the parent {@link MarketplaceAccount} (which is
 * tenant-scoped); same convention as {@link GeneratedProductData}. Immutable (no {@code @Setter}; use
 * {@code toBuilder}).</p>
 */
@Entity
@Table(name = "marketplace_shipping_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_mktshipcfg_account", columnNames = {"marketplace_account_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MarketplaceShippingConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The account these settings belong to (isolation source; UNIQUE = one config per account). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marketplace_account_id", nullable = false)
    private MarketplaceAccount marketplaceAccount;

    // ---- outbound place ----
    @Column(name = "outbound_shipping_place_code", length = 100)
    private String outboundShippingPlaceCode;

    // ---- return center (full address block) ----
    @Column(name = "return_center_code", length = 100)
    private String returnCenterCode;

    @Column(name = "return_charge_name", length = 255)
    private String returnChargeName;

    @Column(name = "return_contact_number", length = 100)
    private String returnContactNumber;

    @Column(name = "return_zip_code", length = 20)
    private String returnZipCode;

    @Column(name = "return_address", length = 500)
    private String returnAddress;

    @Column(name = "return_address_detail", length = 500)
    private String returnAddressDetail;

    @Column(name = "return_charge", precision = 10, scale = 2)
    private BigDecimal returnCharge;

    @Column(name = "delivery_charge_on_return", precision = 10, scale = 2)
    private BigDecimal deliveryChargeOnReturn;

    // ---- delivery settings (seller-chosen, not fetched) ----
    @Column(name = "delivery_method", length = 100)
    private String deliveryMethod;

    @Column(name = "delivery_company_code", length = 100)
    private String deliveryCompanyCode;

    @Column(name = "delivery_charge_type", length = 100)
    private String deliveryChargeType;

    @Column(name = "delivery_charge", precision = 10, scale = 2)
    private BigDecimal deliveryCharge;

    @Column(name = "free_ship_over_amount", precision = 10, scale = 2)
    private BigDecimal freeShipOverAmount;

    /** "Y"/"N" String (not Boolean) — sidesteps the MySQL BOOLEAN↔BIT trap; register sends "Y"/"N". */
    @Column(name = "remote_area_deliverable", length = 1)
    private String remoteAreaDeliverable;

    @Column(name = "union_delivery_type", length = 100)
    private String unionDeliveryType;

    /**
     * Extra info message for made-to-order / installation delivery (FEATURE_2608_06 / 75). Optional
     * (default "사용안함" = blank/null); attached to the register payload only when non-blank. This is the
     * account default — a master/channel {@code shippingOverride} may override it (3-level resolution).
     */
    @Column(name = "extra_info_message", length = 500)
    private String extraInfoMessage;
}
