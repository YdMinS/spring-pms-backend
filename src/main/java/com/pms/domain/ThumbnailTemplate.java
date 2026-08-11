package com.pms.domain;

import com.pms.domain.converter.TemplateElementListConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.List;

/**
 * A tenant-wide thumbnail template library entry: canvas size + an ordered array of
 * {@link TemplateElement}s (painter's algorithm). Rendered by {@link com.pms.service.ThumbnailRenderer}
 * (FEATURE_2608_05).
 *
 * <p>Tenant-isolated via Hibernate {@code @TenantId} (auto-filter on SELECT, auto-set on INSERT — no
 * manual tenant conditions). Templates are NOT seller-owned — they are a shared library. Exactly one
 * template per tenant is the default ({@code isDefault=true}, enforced in
 * {@link com.pms.service.ThumbnailTemplateServiceImpl} like {@code CarrierRate}); thumbnail generation
 * currently resolves the default for every seller (per-seller assignment is a later step).</p>
 */
@Entity
@Table(name = "thumbnail_template")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ThumbnailTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "canvas_width", nullable = false)
    private Integer canvasWidth;

    @Column(name = "canvas_height", nullable = false)
    private Integer canvasHeight;

    /** Optional full-canvas base image (storage key). */
    @Column(name = "background_image_key", length = 500)
    private String backgroundImageKey;

    /** Ordered element array, JSON-serialized into a TEXT column (H2/MySQL portable). */
    @Convert(converter = TemplateElementListConverter.class)
    @Column(name = "elements", columnDefinition = "TEXT")
    private List<TemplateElement> elements;

    @Column(name = "active", nullable = false)
    private Boolean active;

    /**
     * Exactly one template per tenant is the default. NOT nullable → the seeder and service must always
     * set it explicitly (an unset builder default would INSERT null and violate the NOT NULL constraint).
     */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;
}
