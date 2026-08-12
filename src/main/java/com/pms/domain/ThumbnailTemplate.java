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

    /**
     * How the background layer is painted (solid / gradient). NOT nullable → the seeder and service must
     * always set it explicitly (an unset builder default would INSERT null and violate the constraint).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "background_mode", nullable = false, length = 20)
    private BackgroundMode backgroundMode;

    /** Top gradient color ({@code #RRGGBB}); only meaningful for {@link BackgroundMode#GRADIENT_MANUAL}. */
    @Column(name = "gradient_top_color", length = 7)
    private String gradientTopColor;

    /** Bottom gradient color ({@code #RRGGBB}); only meaningful for {@link BackgroundMode#GRADIENT_MANUAL}. */
    @Column(name = "gradient_bottom_color", length = 7)
    private String gradientBottomColor;

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
