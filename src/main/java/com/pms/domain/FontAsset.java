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

    /**
     * CSS font-family fallback stack for detail-page HTML, e.g.
     * {@code 'Nanum Gothic','Malgun Gothic',sans-serif}. Null = no fallback.
     *
     * <p>Two ways a font reaches the buyer's browser: (1) the binary is downloaded via @font-face from
     * {@link #publicWebUrl()}, (2) this stack names faces already installed on the device. The stack is
     * also the tail of the font-family list in case (1), so a failed download still degrades gracefully.</p>
     */
    @Column(name = "web_stack", length = 255)
    private String webStack;

    /** Public URL of the font binary for detail-page @font-face. Null = not downloadable (stack only). */
    @Column(name = "web_url", length = 500)
    private String webUrl;

    /**
     * The URL a buyer's browser can fetch this font from: the explicit {@code webUrl}, else the
     * {@code storageKey} when it is already a public URL (S3-uploaded fonts predating {@code webUrl}).
     * Null = no downloadable binary. Single definition — used by the resolver AND the response mapper.
     */
    public String publicWebUrl() {
        if (webUrl != null && !webUrl.isBlank()) return webUrl;
        return storageKey != null && storageKey.startsWith("http") ? storageKey : null;
    }
}
