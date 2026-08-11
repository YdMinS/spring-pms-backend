package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * A generated (or manually overridden) thumbnail for one product × one seller (FEATURE_2608_05 / 02).
 *
 * <p>Exactly one row per {@code (tenant, product, seller)} — enforced by
 * {@code UNIQUE(tenant_id, product_id, seller_id)} (changeset 003). Regeneration is an <b>upsert</b>:
 * the existing row is rebuilt (same id), never a second insert. Tenant-isolated via Hibernate
 * {@code @TenantId} (auto-filter on SELECT, auto-set on INSERT — no manual tenant conditions).</p>
 *
 * <p>Immutable (no {@code @Setter}) — updates rebuild via {@code toBuilder}, per backend entity rules.</p>
 */
@Entity
@Table(name = "product_thumbnail")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ProductThumbnail extends BaseEntity {

    /** How this thumbnail was produced. */
    public enum Source {
        /** Rendered from a {@link ThumbnailTemplate} by {@link com.pms.service.ThumbnailRenderer}. */
        GENERATED,
        /** Replaced by a manually uploaded image (renderer bypassed, {@code templateId} null). */
        MANUAL_OVERRIDE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    /** Template used for generation (null for MANUAL_OVERRIDE). Plain column, not an FK entity. */
    @Column(name = "template_id")
    private Long templateId;

    /** Result public URL (S3) or disk-relative path (Local), from {@code ImageStorageService.uploadBytes}. */
    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source;

    /** Render/override execution time. Distinct from BaseEntity.updatedAt (explicit regeneration marker). */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;
}
