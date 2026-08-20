package com.pms.domain;

import com.pms.domain.converter.ImageOpListConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.util.List;

/**
 * A reusable image-processing preset in the tenant's library (FEATURE_2608_08): an ordered list of
 * {@link ImageOp}s (v1 = watermark/badge overlays) applied by {@link com.pms.service.ImageProcessor}.
 * Referenced from {@code DetailTemplate.imageProcessingPreset}, so a channel's (seller×platform) detail
 * template resolves its overlays automatically.
 *
 * <p>Tenant-isolated via Hibernate {@code @TenantId} (auto-filter on SELECT, auto-set on INSERT — no
 * manual tenant conditions). Unlike {@code DetailTemplate}/{@code ThumbnailTemplate} there is NO default
 * concept: a preset applies only where a template explicitly references it (no demote logic).</p>
 *
 * <p>⚠️ Immutable (no {@code @Setter}) — updates rebuild via {@code toBuilder}, per backend entity rules.</p>
 */
@Entity
@Table(name = "processing_preset")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ProcessingPreset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant owner. Auto-set/filtered by Hibernate {@code @TenantId} — never set manually in the builder. */
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Ordered op array, JSON-serialized into a TEXT column (H2/MySQL portable). */
    @Convert(converter = ImageOpListConverter.class)
    @Column(name = "operations", columnDefinition = "TEXT")
    private List<ImageOp> operations;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
