package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Preview request for the template editor: render a template with sample values, non-persistent.
 *
 * <p>Resolution rule: use {@code templateId} if present; else use inline {@code template}; if both are
 * null → 400. If both present, {@code templateId} wins.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thumbnail preview request (templateId OR inline template)")
public class ThumbnailPreviewRequest {

    @Schema(description = "Existing template id to preview", nullable = true)
    private Long templateId;

    @Schema(description = "Inline template to preview (used when templateId is null)", nullable = true)
    private ThumbnailTemplateRequest template;

    @Schema(description = "Sample text bindings, e.g. {brandName, productName}")
    private Map<String, String> sampleBindings;
}
