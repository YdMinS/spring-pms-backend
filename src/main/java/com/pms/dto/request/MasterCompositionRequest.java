package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Atomic "replace the master's component set AND its full option list" request (2609_64).
 *
 * <p>The component set and every option's quantity vector validate each other (set equality), so saving them
 * separately makes both unchangeable. This is the only request that carries both.</p>
 *
 * <p>⚠️ <b>Do not add fields.</b> An option's delivery/box override, {@code categoryAttributes},
 * {@code categoryNotices} and {@code stockQuantity} are deliberately absent — they are carried over from the
 * existing row. {@code stockQuantity} in particular means "null = clear it" on the per-option update path, so
 * adding it here would silently wipe a stock the composition screen never asked about.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master composition (components + full option list) atomic replace request")
public class MasterCompositionRequest {

    @NotEmpty(message = "componentProductIds is required")
    @Schema(description = "새 구성상품 집합. 순서는 매칭에 영향을 주지 않는다")
    private List<Long> componentProductIds;

    @NotEmpty(message = "options is required")
    @Valid
    @Schema(description = "이 마스터가 저장 후 가질 옵션 전체. 여기 없는 기존 옵션은 삭제된다")
    private List<OptionSpec> options;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One option after the change")
    public static class OptionSpec {

        @Schema(description = "기존 옵션 id. null = 새로 만든다", nullable = true)
        private Long optionId;

        @NotBlank(message = "name is required")
        @Schema(description = "Option name", example = "2세트")
        private String name;

        @NotEmpty(message = "items is required")
        @Valid
        @Schema(description = "새 구성상품 집합 전체를 덮는 수량 벡터")
        private List<MasterOptionRequest.OptionItem> items;
    }
}
