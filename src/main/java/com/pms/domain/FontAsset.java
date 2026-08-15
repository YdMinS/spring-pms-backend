package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A renderable font: system-shared (bundled, {@code tenantId=null}) or tenant-uploaded.
 *
 * <p>⚠️ Intentionally does NOT use {@code @TenantId} — unlike the other tenant-owned entities.
 * A shared system font has {@code tenantId=null}, and Hibernate's {@code @TenantId} filter cannot
 * match null, so it would hide system fonts from every tenant. Instead {@link com.pms.repository.FontAssetRepository}
 * queries explicitly: {@code tenant_id IS NULL OR tenant_id = :tid}. This is the same deliberate
 * exception documented for {@code User} (see backend CLAUDE.md §9).</p>
 *
 * <p>{@code storageKey}: for {@link FontSource#BUNDLED} a classpath resource path
 * ({@code fonts/xxx.ttf}); for {@link FontSource#UPLOADED} the value returned by
 * {@code ImageStorageService.uploadBytes} (disk path or S3 URL).</p>
 */
@Entity
@Table(name = "font_asset")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class FontAsset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = system-shared font (visible to all tenants). NOT {@code @TenantId} — see class doc. */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    /** Logical family identifier used when deriving the AWT {@code Font}. */
    @Column(name = "family_key", nullable = false, length = 255)
    private String familyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private FontSource source;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;
}
