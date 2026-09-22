package com.pms.domain;

import com.pms.domain.converter.MapStringConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * ProductListingOption entity representing an option variant of a platform listing.
 *
 * Business Logic:
 * - optionName: Option label (e.g., "Size M", "Color Red")
 * - sellingPrice: Selling price for this option (decimal, 2 decimal places)
 * - platformOptionId: Platform-specific option ID (e.g., Coupang option ID)
 * - productListing: Reference to the parent ProductListing
 *
 * Relationships:
 * - N ProductListingOptions : 1 ProductListing
 * - N ProductListingOptions : 1 MasterProductOption (masterProductOption FK; null = 채널 전용 옵션)
 *
 * Business Rules:
 * - This is the actual business unit for order/shipment processing
 * - 🔴 구성품(물품 × 수량)은 이 옵션이 갖지 않는다 (FEATURE_2609_71). masterProductOption 을 타고
 *   master_product_option_item 에서 읽으며, 읽는 창구는 CellBomResolver 하나다. 채널 전용 옵션은
 *   마스터가 없어 구성품을 알 수 없다 — 「0개」가 아니라 「미매핑」이다.
 * - sellingPrice is used as the base for margin calculation:
 *   Margin = sellingPrice - (product costs × qty) - commission - delivery - package
 *
 * <p>Audit (104 Step 1): extends {@link BaseEntity} — same reasoning as {@link ProductListing}; here it also
 * dates the approval data ({@code approval_status}/{@code platform_option_id}) written by {@code fetchStatus}.</p>
 *
 * @see com.pms.domain.ProductListing for the parent listing
 * @see com.pms.domain.MasterProductOptionItem 구성품의 정본 (masterProductOption 을 타고 읽는다)
 */
@Entity
@Table(name = "product_listing_option")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Schema(description = "Product listing option (SKU variant with selling price)")
public class ProductListingOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Product listing option ID", example = "1")
    private Long id;

    /**
     * Reference to parent ProductListing.
     * Lazy-loaded. Required field.
     * Cascade not needed - managed via service layer.
     *
     * @see com.pms.domain.ProductListing
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id", nullable = false)
    @Schema(description = "Parent product listing")
    private ProductListing productListing;

    /**
     * Option name/label (max 255 chars).
     * Examples: "Size M", "Color Red", "Bundle Pack"
     * Required field.
     */
    @Column(length = 255, nullable = false, name = "option_name")
    @Schema(description = "Option name/label", example = "Size M")
    private String optionName;

    /**
     * Selling price for this option.
     * Precision: 10 digits, 2 decimal places (e.g., 12345.67).
     * This is the base price for margin calculation.
     * Required field.
     */
    @Column(nullable = false, precision = 10, scale = 2, name = "selling_price")
    @Schema(description = "Selling price (base for margin calculation)", example = "12999.99")
    private BigDecimal sellingPrice;

    /**
     * Display "original" (strike-through) price for Coupang register (73). Reverse-calculated from
     * {@code sellingPrice} and the seller×platform display discount rate: {@code sellingPrice / (1 − rate)}.
     * Nullable (rate=0 → equals sellingPrice; register falls back to sellingPrice when null).
     */
    @Column(precision = 10, scale = 2, name = "original_price")
    @Schema(description = "Display original (strike-through) price", example = "16249.99")
    private BigDecimal originalPrice;

    /**
     * Platform-specific option ID (e.g., Coupang option ID).
     * Max 255 chars. Nullable (not all platforms provide this).
     *
     * Examples:
     * - Coupang: "1234567890"
     * - NAVER: "opt_12345"
     */
    @Column(length = 255, nullable = true, name = "platform_option_id")
    @Schema(description = "Platform option ID", example = "opt_12345")
    private String platformOptionId;

    /**
     * Approval state on the market (FEATURE_2608_06 / 3c) — the source of truth for option approval.
     * New DRAFT options default to {@link OptionApprovalStatus#NOT_APPROVED}; pre-existing live options are
     * backfilled to {@code APPROVED} (changeset 013 defaultValue). {@code fetchStatus} flips matched options
     * to {@code APPROVED} after the market approves them.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20, nullable = false)
    @Builder.Default
    @Schema(description = "Option approval state", example = "NOT_APPROVED")
    private OptionApprovalStatus approvalStatus = OptionApprovalStatus.NOT_APPROVED;

    /**
     * Coupang option-update id (sellerProductItemId), max 255 chars. Nullable — filled by {@code fetchStatus}
     * after approval (alongside {@link #platformOptionId}=vendorItemId, both approval-result data).
     * Distinct from {@link #platformOptionId} which is the vendorItemId used for order mapping (unchanged).
     */
    @Column(length = 255, nullable = true, name = "seller_product_item_id")
    @Schema(description = "Coupang seller product item id (for option updates)", example = "555666")
    private String sellerProductItemId;

    /**
     * Per-channel active flag (FEATURE_2608_06 / 42). The master is the single option universe, but a channel
     * (market) may carry a different subset of options: a channel cell copies <em>all</em> master options
     * (backward-compatible) and then toggles this flag per channel. {@code active=false} only excludes the option
     * from the market register/update payload — the row is <b>kept</b> (re-activation + order mapping preserved).
     *
     * <p>Two default roles (not duplicated): entity {@code @Builder.Default = true} = the create path
     * (channel-add copy) default; changeset 028 {@code defaultValueBoolean:true} backfills pre-existing live rows.
     * ⚠️ 006 BIT trap: boolean needs an explicit MySQL physical type (BIT(1)) — see changeset 028.</p>
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    @Schema(description = "Per-channel active flag (excluded from market payload when false)", example = "true")
    private Boolean active = true;

    /**
     * Per-channel stock override (FEATURE_2608_06 / 102). {@code null} = inherit the master option's
     * {@code stockQuantity}; if that is null too the payload falls back to 9999
     * ({@code ListingStockPolicy.DEFAULT_STOCK_QUANTITY}). Resolution = channel ?? master ?? 9999.
     *
     * <p>⚠️ D5: the override may not exceed the master option's value (ceiling = master ?? 9999) — the write
     * path rejects a larger value, and lowering the master clamps the channels that are above it. Clamping
     * never auto-pushes to the market ([수정 요청] does that).</p>
     */
    @Column(name = "stock_quantity")
    @Schema(description = "Per-channel stock override; null = inherit the master option's stock", example = "30")
    private Integer stockQuantity;

    /**
     * Origin of {@link #sellingPrice} (FEATURE_2609_19 / D1). {@code AUTO} = the margin reverse-calc value
     * (a cell [재생성] recomputes it); {@code MANUAL_OVERRIDE} = a price the user set for this channel only,
     * which a regeneration must leave alone (D2 — the same rule the detail HTML override already follows).
     *
     * <p>⚠️ {@link #sellingPrice} is ALWAYS the effective price whatever this says — never write read code
     * that reinterprets the price based on this field. It exists so the regeneration knows what to skip and
     * so the matrix can mark the cell as manually priced.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 20)
    @Builder.Default
    @Schema(description = "Origin of the selling price (AUTO = calculated, MANUAL_OVERRIDE = user-set)",
            example = "AUTO")
    private GeneratedContentSource priceSource = GeneratedContentSource.AUTO;

    /**
     * 이 셀 옵션이 가리키는 마스터 옵션(2609_22/D1). null = 채널 전용 옵션 — 마스터 전파가 건드리지 않는다(D2).
     * ⚠️ 마스터↔셀 매칭은 이 FK 가 유일한 축이다. optionName 으로 다시 매칭하는 코드를 만들지 말 것.
     *
     * <p>FK is {@code ON DELETE SET NULL} (changeset 060/D22): deleting the master option leaves this row
     * alive as a channel-only (inactive) option — an approved Coupang option cannot be deleted there.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_product_option_id", nullable = true)
    @Schema(description = "Linked master option; null = channel-only option")
    private MasterProductOption masterProductOption;

    /**
     * 옵션명의 출처(2609_22/D3). AUTO = 마스터 옵션명을 따름, MANUAL_OVERRIDE = 이 채널에서 정한 이름.
     *
     * <p>⚠️ {@link #optionName} is ALWAYS the effective name whatever this says — the same contract as
     * {@link #priceSource} / {@link #sellingPrice}. It exists so a master rename knows what to skip (D4).</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "option_name_source", nullable = false, length = 20)
    @Builder.Default
    @Schema(description = "Origin of the option name (AUTO = follows the master, MANUAL_OVERRIDE = channel-set)",
            example = "AUTO")
    private GeneratedContentSource optionNameSource = GeneratedContentSource.AUTO;

    /**
     * 이 셀 옵션만의 카테고리 속성 override(2609_22/D5). 병합 = 셀옵션 ?? 마스터옵션 ?? 마스터.
     * A channel-only option has no master option, so this is its only place to carry a required attribute.
     */
    @Convert(converter = MapStringConverter.class)
    @Column(name = "category_attributes", columnDefinition = "TEXT")
    @Schema(description = "Per-cell-option category attribute override")
    private Map<String, String> categoryAttributes;

    /** 이 셀 옵션만의 고시 override(2609_22/D5). 병합 규칙은 위와 동일. */
    @Convert(converter = MapStringConverter.class)
    @Column(name = "category_notices", columnDefinition = "TEXT")
    @Schema(description = "Per-cell-option category notice override")
    private Map<String, String> categoryNotices;

    /**
     * 마켓에 <b>실제로 걸린 판매가</b>(FEATURE_2609_39 / PLAN D5). null = 알 수 없음(한 번도 밀린 적이 없거나
     * 089 백필 대상이 아니었던 행).
     *
     * <p>⚠️ {@link #sellingPrice} 와 역할이 다르다: 그쪽은 <b>로컬</b> 값이라 재계산이 덮어쓴다. 재계산 후에는
     * "지금 마켓에서 얼마에 팔리는지"도 "무엇이 아직 안 밀렸는지"도 이 칸으로만 알 수 있다.</p>
     *
     * <p>🔴 채우는 자리는 넷뿐이다(PLAN D19): 옵션 편집 화면의 <b>전송 성공</b> · 등록 승인 동기화(식별자를 처음
     * 받는 순간) · 편입(마켓에서 읽어온 실가격) · 일괄 마켓 반영. 전송하지 않은 옵션에 이 값을 쓰면 거짓이 된다.</p>
     */
    @Column(precision = 10, scale = 2, name = "market_price")
    @Schema(description = "Price actually live on the marketplace", example = "12999.99")
    private BigDecimal marketPrice;

    /** {@link #marketPrice} 가 마켓에 전송된 시각. 과거 시각은 알 수 없으므로 백필하지 않는다(NULL 허용). */
    @Column(name = "market_price_at")
    @Schema(description = "When the market price was last pushed")
    private LocalDateTime marketPriceAt;

    /** 채널 전용 옵션 = 마스터에 대응 옵션이 없다(2609_22/D2). */
    public boolean isChannelOnly() {
        return masterProductOption == null;
    }

    /**
     * True = this option physically exists on the marketplace: Coupang issued a vendorItemId, or it was
     * approved at some point. Such an option cannot be removed there (approved options are not deletable),
     * so 87 forbids unchecking it and 88 locks the checkbox.
     *
     * <p>⚠️ Deliberately does NOT include {@code active}. 84's lock adds that third term on top of this one
     * (see {@code MasterProductServiceImpl#isOnMarket}); 87 must not, or every active option of a pushed cell
     * would be locked and "turn on, then undo before re-registering" would be impossible.</p>
     */
    public boolean isMarketRegistered() {
        return platformOptionId != null || approvalStatus == OptionApprovalStatus.APPROVED;
    }
}
