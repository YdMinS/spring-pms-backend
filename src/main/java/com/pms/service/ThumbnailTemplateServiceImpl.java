package com.pms.service;

import com.pms.domain.TemplateElement;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.request.ThumbnailPreviewRequest;
import com.pms.dto.request.ThumbnailTemplateRequest;
import com.pms.dto.response.ThumbnailTemplateResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ThumbnailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final ThumbnailTemplateRepository templateRepository;
    private final ThumbnailRenderer renderer;

    @Override
    @Transactional
    public ThumbnailTemplateResponse create(ThumbnailTemplateRequest request) {
        ThumbnailTemplate template = ThumbnailTemplate.builder()
                .sellerId(request.getSellerId())
                .name(request.getName())
                .canvasWidth(request.getCanvasWidth())
                .canvasHeight(request.getCanvasHeight())
                .backgroundImageKey(request.getBackgroundImageKey())
                .elements(request.getElements() == null ? List.of() : request.getElements())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .build();
        return toResponse(templateRepository.save(template));
    }

    @Override
    public ThumbnailTemplateResponse get(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    public List<ThumbnailTemplateResponse> list(Long sellerId) {
        List<ThumbnailTemplate> templates = sellerId == null
                ? templateRepository.findAll()
                : templateRepository.findBySellerId(sellerId);
        return templates.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public ThumbnailTemplateResponse update(Long id, ThumbnailTemplateRequest request) {
        ThumbnailTemplate existing = findOrThrow(id);
        ThumbnailTemplate updated = existing.toBuilder()
                .sellerId(request.getSellerId() != null ? request.getSellerId() : existing.getSellerId())
                .name(request.getName() != null ? request.getName() : existing.getName())
                .canvasWidth(request.getCanvasWidth() != null ? request.getCanvasWidth() : existing.getCanvasWidth())
                .canvasHeight(request.getCanvasHeight() != null ? request.getCanvasHeight() : existing.getCanvasHeight())
                .backgroundImageKey(request.getBackgroundImageKey() != null
                        ? request.getBackgroundImageKey() : existing.getBackgroundImageKey())
                .elements(request.getElements() != null ? request.getElements() : existing.getElements())
                .active(request.getActive() != null ? request.getActive() : existing.getActive())
                .build();
        return toResponse(templateRepository.save(updated));
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
                    .backgroundImageKey(inline.getBackgroundImageKey())
                    .elements(inline.getElements() == null ? List.of() : inline.getElements())
                    .active(Boolean.TRUE)
                    .build();
        } else {
            throw new IllegalArgumentException("Provide either templateId or an inline template");
        }

        Map<String, String> textBindings = request.getSampleBindings() == null
                ? Map.of() : request.getSampleBindings();
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
        return ThumbnailTemplateResponse.builder()
                .id(t.getId())
                .sellerId(t.getSellerId())
                .name(t.getName())
                .canvasWidth(t.getCanvasWidth())
                .canvasHeight(t.getCanvasHeight())
                .backgroundImageKey(t.getBackgroundImageKey())
                .elements(elements)
                .active(t.getActive())
                .build();
    }
}
