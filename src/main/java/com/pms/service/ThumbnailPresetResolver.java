package com.pms.service;

import com.pms.domain.ImageOp;
import com.pms.domain.ProcessingPreset;
import com.pms.domain.TemplateElement;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.ProcessingPresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the image-processing preset of a thumbnail template's product photo (FEATURE_2609_81).
 *
 * <p><b>Rule.</b> Only the FIRST {@code type=image, bind=productImage} element (the base layer, same rule
 * as {@link ThumbnailRenderer#firstProductImageElement}) carries the preset. Its
 * {@code processingPresetId} is read; the value on any other element is ignored. The caller processes the
 * product photo once with the returned ops and binds the result to {@code productImage}, so every element
 * that draws the product photo gets the same processed image.</p>
 *
 * <p><b>Never fails a render.</b> No preset → empty list. A preset id that no longer resolves (deleted /
 * other tenant) or a preset without operations → warn log + empty list (the photo is rendered
 * unprocessed). Same rule as the detail generator's preset lookup.</p>
 *
 * <p>Callers: {@code ListingAssetServiceImpl.regenerateAssets} (generation) and
 * {@code ThumbnailTemplateServiceImpl.preview} (editor preview).
 * Usage: {@code List<ImageOp> ops = thumbnailPresetResolver.productImageOps(template);
 * byte[] photo = ops.isEmpty() ? base : imageProcessor.process(imageDecodeSupport.flattenOnWhite(base), ops);}</p>
 *
 * <p>⚠️ ❌ Do not apply the preset to text, background or fixed images. ❌ Do not upload the processed
 * photo — it lives only in memory until the thumbnail JPEG is rendered.
 * File: {@code service/ThumbnailPresetResolver.java}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailPresetResolver {

    private final ProcessingPresetRepository processingPresetRepository;

    /** Ops to apply to the product photo; empty = render the photo as-is. Never null. */
    public List<ImageOp> productImageOps(ThumbnailTemplate template) {
        TemplateElement base = ThumbnailRenderer.firstProductImageElement(template.getElements());
        if (base == null || base.getProcessingPresetId() == null) {
            return List.of();
        }
        Long presetId = base.getProcessingPresetId();
        ProcessingPreset preset = processingPresetRepository.findScopedById(presetId).orElse(null);
        if (preset == null) {
            log.warn("Thumbnail product-photo preset {} not found (template {}) — rendering the photo unprocessed",
                    presetId, template.getId());
            return List.of();
        }
        List<ImageOp> ops = preset.getOperations();
        if (ops == null || ops.isEmpty()) {
            log.warn("Thumbnail product-photo preset {} has no operations (template {}) — rendering the photo unprocessed",
                    presetId, template.getId());
            return List.of();
        }
        return ops;
    }
}
