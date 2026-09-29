package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A master product that contains at least one of the requested products (2609_79 / UX D74·D79), with its
 * whole component combination — the 「마켓 상품으로 시작」 screen lists these so a person can judge partial
 * overlaps (e.g. "생수 500ml" vs "생수 500ml + 녹차").
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master product containing at least one of the given products, with its components")
public class MasterProductByAnyComponentResponse {

    @Schema(description = "Master product ID", example = "5")
    private Long id;

    @Schema(description = "Master product name", example = "노브랜드 생수 묶음")
    private String name;

    @Schema(description = "Every component of the master (component row order)")
    private List<Component> components;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One component product of the master")
    public static class Component {

        @Schema(description = "Product ID", example = "12")
        private Long productId;

        @Schema(description = "Product name", example = "생수 500ml")
        private String productName;
    }
}
