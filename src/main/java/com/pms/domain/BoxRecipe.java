package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * Box memory — "this combination of items went into that box" (FEATURE_2609_40 / PLAN D22 · D23 · D24).
 *
 * <p>🔴 {@link #recipeKey} is a SORTED string and the sorting IS the rule: {@code (product_id:quantity)}
 * pairs sorted by product id ascending and joined with {@code '|'} (e.g. {@code "12:2|45:1"}). If the same
 * combination produced a different key depending on the scan order, the memory would never match again.
 * {@link com.pms.service.packing.BoxRecipeKey} is the single owner of that rule — the side that writes the
 * memory (packing console) and the side that looks it up must both go through it.</p>
 *
 * <p>🔴 No order, option or seller is part of the key (D22): the same items in the same quantities go into
 * the same box, whether they came from one order or several.</p>
 *
 * <p>⚠️ The box a master/option carries for PRICING is a different axis entirely — it is a cost input, not a
 * recommendation, and is never consulted here (D22).</p>
 *
 * <p>⚠️ Rows are written automatically when a parcel is completed (D24); there is deliberately no
 * registration screen — if recording were a separate chore nobody would do it.</p>
 *
 * @see com.pms.service.packing.BoxRecipeService the only way in and out of this table
 */
@Entity
@Table(name = "box_recipe",
        uniqueConstraints = @UniqueConstraint(name = "uq_box_recipe_key_package",
                columnNames = {"tenant_id", "recipe_key", "package_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BoxRecipe extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ⚠️ Never set by hand — Hibernate fills it on INSERT and filters SELECTs (OrderShipment precedent).
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 🔴 Sorted combination string built ONLY by {@link com.pms.service.packing.BoxRecipeKey}. */
    @Column(name = "recipe_key", nullable = false, length = 500)
    private String recipeKey;

    /**
     * The box that was actually used for that combination.
     * ⚠️ Named {@code boxPackage} like {@link ShipmentParcel#getBoxPackage()} — {@code package} is a Java
     * keyword and a trailing-underscore field name collides with Spring Data's path separator.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private Package boxPackage;

    /** How many times this combination was packed into this box. Drives the candidate ordering (D23). */
    @Column(name = "use_count", nullable = false)
    private Integer useCount;

    /** Tie-breaker for equal use counts, and the basis for "most recent first" (D23). */
    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;
}
