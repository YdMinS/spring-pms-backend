package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * 쿠팡 계정 1개의 배송설정 (FEATURE_2608_06 / 72) — 출고지, 반품지(주소 블록 전체), 배송 설정.
 * 담기는 값 어휘가 전부 WING 코드다({@code deliveryMethod}·{@code deliveryChargeType}·
 * {@code unionDeliveryType}·출고지/반품지 코드) → 중립 core 로 올리지 않는다.
 * 네이버는 {@code naver_shipping_config} 를 나란히 추가한다 (FEATURE_2609_26 / PLAN D17).
 *
 * <p>값은 플랫폼 조회({@code ShippingPlaceProvider})에서 올 수도 있고 수동 입력일 수도 있다 — 이 엔티티는
 * 구분하지 않는다. 계정당 1개(배송 설정은 계정 단위, listing 단위 override 는 범위 밖)이며 UNIQUE
 * {@code marketplace_account_id}({@code @OneToOne})로 강제된다.</p>
 *
 * <p>All fields are nullable (partial save allowed while the wizard fills in); register (73) guards any missing
 * required value with a 400. {@code remoteAreaDeliverable} is a "Y"/"N" String (not Boolean) to sidestep the
 * MySQL BOOLEAN↔BIT trap (changeset 006), and register transmits the same "Y"/"N".</p>
 *
 * <p>Immutable (no {@code @Setter}; use {@code toBuilder}).</p>
 */
@Entity
@Table(name = "coupang_shipping_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_mktshipcfg_account", columnNames = {"marketplace_account_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class CoupangShippingConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Tenant dimension (PLAN D25) — same rule as the sibling 1:1 child coupang_account_credential.
    // Hibernate auto-sets this on INSERT and auto-filters SELECTs — do NOT set it in the builder
    // (assigned != current raises) and do NOT add manual tenant conditions.
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

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
