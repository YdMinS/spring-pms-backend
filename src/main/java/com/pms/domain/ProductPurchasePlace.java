package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * "This product can be bought at this place" (FEATURE_2609_76 / D20) — one row per (product, place).
 *
 * <p>A separate link table, not a list packed into a product column, so that a later "filter by purchase
 * place" (D4) is a plain join and "is this place still used?" (D9) is a count.</p>
 *
 * <p>No {@code @TenantId} and no {@code BaseEntity}: a pure join row, like {@link MasterProductComponent}.
 * Isolation flows through the product and the place, both tenant-filtered. Updates are delete + insert of
 * the difference ({@code ProductServiceImpl.replacePurchasePlaces}).</p>
 */
@Entity
@Table(name = "product_purchase_place",
        uniqueConstraints = @UniqueConstraint(name = "uq_ppp_product_place",
                columnNames = {"product_id", "purchase_place_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ProductPurchasePlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_place_id", nullable = false)
    private PurchasePlace purchasePlace;
}
