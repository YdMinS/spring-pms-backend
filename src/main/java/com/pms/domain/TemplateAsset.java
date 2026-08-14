package com.pms.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

/**
 * A reusable fixed image asset in the tenant's thumbnail library (watermark, "free shipping" /
 * "dawn delivery" badges, etc.). Uploaded once, then referenced by {@code TemplateElement.src} in any
 * number of templates — the renderer's {@code loadStored} reads {@link #storageKey} directly.
 *
 * <p>⚠️ Unlike {@link FontAsset} (which shares system rows via {@code tenantId=null}), a template asset
 * is always tenant-owned: {@code @TenantId} auto-filters SELECTs and auto-stamps INSERTs, so there is no
 * system/shared concept and no manual tenant query. {@code tenant_id} is NOT NULL.</p>
 *
 * <p>{@code storageKey} = the value returned by {@code ImageStorageService.uploadBytes} (disk-relative
 * path on Local, public https URL on S3). It is exactly what a fixed-image element's {@code src} carries,
 * so the front end (phase 16) puts this value into both the canvas {@code <img>} and the element source.</p>
 *
 * <p>Immutable (no {@code @Setter}) — updates rebuild via {@code toBuilder}, per backend entity rules.</p>
 */
@Entity
@Table(name = "thumbnail_asset")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class TemplateAsset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant owner. Auto-set/filtered by Hibernate {@code @TenantId} — never set manually in the builder. */
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Display name, derived from the uploaded filename (extension stripped). */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** {@code ImageStorageService.uploadBytes} return value — goes verbatim into {@code element.src}. */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "content_type", length = 100)
    private String contentType;
}
