package com.pms.domain;

import com.pms.domain.converter.StringListConverter;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

/**
 * Append-only push-time tag snapshot for a channel cell (33). Each row is the {@code merged} tag list
 * (channel-first, master appended) captured at the moment a cell is successfully pushed to the market — the
 * time series analysts join against sales (tag changes ↔ revenue over time). The master tags are already
 * folded into every snapshot, so the master needs no separate history.
 *
 * <p>Tenant isolation: this is a <b>pure child</b> of {@link ProductListing} — no {@code @TenantId} of its own.
 * It is only ever recorded after the parent listing has passed {@code findScopedById} (a tenant check), and it
 * is scoped exclusively through the {@code product_listing_id} FK, so no direct tenant filter is needed.</p>
 */
@Entity
@Table(name = "product_listing_tag_revision")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductListingTagRevision extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_listing_id", nullable = false)
    private ProductListing productListing;

    /** The merged tag list at push time (JSON TEXT, H2/MySQL portable). Ordered. */
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> tags;
}
