package com.pms.service;

import com.pms.domain.BackgroundMode;
import com.pms.domain.TemplateElement;
import com.pms.domain.TemplateField;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.ThumbnailPreviewRequest;
import com.pms.dto.request.ThumbnailTemplateRequest;
import com.pms.dto.response.ThumbnailTemplateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ThumbnailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thumbnail template CRUD + non-persistent preview. Tenant isolation is automatic via {@code @TenantId}
 * on {@link ThumbnailTemplate} — no manual tenant conditions here.
 *
 * <p>⚠️ Entity is immutable (no setters): updates rebuild via {@code toBuilder} (partial — null request
 * fields keep existing values).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ThumbnailTemplateServiceImpl implements ThumbnailTemplateService {

    /** Reserved field keys whose defaultValue may be blank (brand/product name filled by the generate UI). */
    private static final Set<String> BUILTIN_FIELD_KEYS = Set.of("brandName", "productName");

    private final ThumbnailTemplateRepository templateRepository;
    private final ThumbnailRenderer renderer;

    @Override
    @Transactional
    public ThumbnailTemplateResponse create(ThumbnailTemplateRequest request) {
        boolean makeDefault = Boolean.TRUE.equals(request.getIsDefault());
        if (makeDefault) {
            demoteExistingDefault(null);
        }
        BackgroundMode mode = request.getBackgroundMode() != null
                ? request.getBackgroundMode() : BackgroundMode.WHITE;
        validateGradientColors(mode, request.getGradientTopColor(), request.getGradientBottomColor());
        validateFields(request.getFields());
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .name(request.getName())
                .canvasWidth(request.getCanvasWidth())
                .canvasHeight(request.getCanvasHeight())
                .backgroundMode(mode)
                .gradientTopColor(request.getGradientTopColor())
                .gradientBottomColor(request.getGradientBottomColor())
                .elements(request.getElements() == null ? List.of() : request.getElements())
                .fields(request.getFields() == null ? List.of() : request.getFields())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .isDefault(makeDefault)
                .build();
        return toResponse(templateRepository.save(template));
    }

    @Override
    public ThumbnailTemplateResponse get(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    public List<ThumbnailTemplateResponse> list() {
        return templateRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public ThumbnailTemplateResponse update(Long id, ThumbnailTemplateRequest request) {
        ThumbnailTemplate existing = findOrThrow(id);
        boolean makeDefault = Boolean.TRUE.equals(request.getIsDefault());
        if (makeDefault) {
            demoteExistingDefault(id);
        }
        BackgroundMode mode = request.getBackgroundMode() != null
                ? request.getBackgroundMode() : existing.getBackgroundMode();
        String topColor = request.getGradientTopColor() != null
                ? request.getGradientTopColor() : existing.getGradientTopColor();
        String bottomColor = request.getGradientBottomColor() != null
                ? request.getGradientBottomColor() : existing.getGradientBottomColor();
        validateGradientColors(mode, topColor, bottomColor);
        validateFields(request.getFields());
        ThumbnailTemplate updated = existing.toBuilder()
                .name(request.getName() != null ? request.getName() : existing.getName())
                .canvasWidth(request.getCanvasWidth() != null ? request.getCanvasWidth() : existing.getCanvasWidth())
                .canvasHeight(request.getCanvasHeight() != null ? request.getCanvasHeight() : existing.getCanvasHeight())
                .backgroundMode(mode)
                .gradientTopColor(topColor)
                .gradientBottomColor(bottomColor)
                .elements(request.getElements() != null ? request.getElements() : existing.getElements())
                .fields(request.getFields() != null ? request.getFields() : existing.getFields())
                .active(request.getActive() != null ? request.getActive() : existing.getActive())
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : existing.getIsDefault())
                .build();
        return toResponse(templateRepository.save(updated));
    }

    /**
     * Enforce one default per tenant (CarrierRate pattern): demote the current default before promoting
     * a new one. {@code keepId} = the template being promoted (skip if it is already the default).
     */
    private void demoteExistingDefault(Long keepId) {
        templateRepository.findByIsDefaultTrueAndActiveTrue().ifPresent(current -> {
            if (!current.getId().equals(keepId)) {
                templateRepository.save(current.toBuilder().isDefault(false).build());
            }
        });
    }

    /**
     * Cross-field rule: {@code GRADIENT_MANUAL} needs both gradient colors (→400 otherwise). Other modes
     * ignore the colors. Color format itself is validated at render time by {@code parseColor}.
     */
    private void validateGradientColors(BackgroundMode mode, String top, String bottom) {
        if (mode == BackgroundMode.GRADIENT_MANUAL
                && (top == null || top.isBlank() || bottom == null || bottom.isBlank())) {
            throw new IllegalArgumentException("수동 그라데이션은 두 색이 필요합니다");
        }
    }

    /**
     * Field rules (→400 on violation): every {@code key} is non-blank and unique; custom (non-reserved)
     * fields require a non-blank {@code defaultValue} (reserved keys brandName/productName may be blank —
     * the generate UI fills them). {@code null} = keep-existing (partial update), so it passes untouched.
     */
    private void validateFields(List<TemplateField> fields) {
        if (fields == null) {
            return;
        }
        Set<String> seenKeys = new HashSet<>();
        for (TemplateField field : fields) {
            if (!StringUtils.hasText(field.getKey())) {
                throw new IllegalArgumentException("필드 키는 필수입니다");
            }
            if (!seenKeys.add(field.getKey())) {
                throw new IllegalArgumentException("필드 키가 중복되었습니다: " + field.getKey());
            }
            if (!BUILTIN_FIELD_KEYS.contains(field.getKey())
                    && !StringUtils.hasText(field.getDefaultValue())) {
                throw new IllegalArgumentException("커스텀 필드는 기본값이 필요합니다");
            }
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        templateRepository.delete(findOrThrow(id));
    }

    @Override
    public byte[] preview(ThumbnailPreviewRequest request) {
        ThumbnailTemplate template;
        if (request.getTemplateId() != null) {
            template = findOrThrow(request.getTemplateId());
        } else if (request.getTemplate() != null) {
            ThumbnailTemplateRequest inline = request.getTemplate();
            if (inline.getCanvasWidth() == null || inline.getCanvasHeight() == null) {
                throw new IllegalArgumentException("Inline template requires canvasWidth and canvasHeight");
            }
            template = ThumbnailTemplate.builder()
                    .name(inline.getName())
                    .canvasWidth(inline.getCanvasWidth())
                    .canvasHeight(inline.getCanvasHeight())
                    .backgroundMode(inline.getBackgroundMode() != null
                            ? inline.getBackgroundMode() : BackgroundMode.WHITE)
                    .gradientTopColor(inline.getGradientTopColor())
                    .gradientBottomColor(inline.getGradientBottomColor())
                    .elements(inline.getElements() == null ? List.of() : inline.getElements())
                    .fields(inline.getFields() == null ? List.of() : inline.getFields())
                    .active(Boolean.TRUE)
                    .build();
        } else {
            throw new IllegalArgumentException("Provide either templateId or an inline template");
        }

        // Field defaults first (so custom fields preview even without sampleBindings), sampleBindings overlay.
        Map<String, String> textBindings = new HashMap<>();
        if (template.getFields() != null) {
            for (TemplateField field : template.getFields()) {
                if (StringUtils.hasText(field.getDefaultValue())) {
                    textBindings.put(field.getKey(), field.getDefaultValue());
                }
            }
        }
        if (request.getSampleBindings() != null) {
            request.getSampleBindings().forEach((key, value) -> {
                if (StringUtils.hasText(value)) {
                    textBindings.put(key, value);
                }
            });
        }
        Map<String, byte[]> imageBindings = placeholderImageBindings(template);
        return renderer.render(template, textBindings, imageBindings);
    }

    /** For every image element bound (not sourced), supply a gray placeholder so preview shows a box. */
    private Map<String, byte[]> placeholderImageBindings(ThumbnailTemplate template) {
        Map<String, byte[]> bindings = new HashMap<>();
        if (template.getElements() == null) {
            return bindings;
        }
        byte[] placeholder = null;
        for (TemplateElement e : template.getElements()) {
            boolean boundImage = "image".equalsIgnoreCase(e.getType())
                    && (e.getSrc() == null || e.getSrc().isBlank())
                    && e.getBind() != null;
            if (boundImage && !bindings.containsKey(e.getBind())) {
                if (placeholder == null) {
                    placeholder = grayPlaceholder();
                }
                bindings.put(e.getBind(), placeholder);
            }
        }
        return bindings;
    }

    private byte[] grayPlaceholder() {
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(0xCC, 0xCC, 0xCC));
            g.fillRect(0, 0, 400, 400);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build placeholder image", e);
        }
        return out.toByteArray();
    }

    private ThumbnailTemplate findOrThrow(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ThumbnailTemplate", id));
    }

    private ThumbnailTemplateResponse toResponse(ThumbnailTemplate t) {
        List<TemplateElement> elements = t.getElements() == null ? new ArrayList<>() : t.getElements();
        List<TemplateField> fields = t.getFields() == null ? new ArrayList<>() : t.getFields();
        return ThumbnailTemplateResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .canvasWidth(t.getCanvasWidth())
                .canvasHeight(t.getCanvasHeight())
                .backgroundMode(t.getBackgroundMode())
                .gradientTopColor(t.getGradientTopColor())
                .gradientBottomColor(t.getGradientBottomColor())
                .elements(elements)
                .fields(fields)
                .active(t.getActive())
                .isDefault(t.getIsDefault())
                .build();
    }
}
