package com.pms.dto.request;

import com.pms.domain.ListingUpdateItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Optional body of a [수정 요청] (FEATURE_2611_25 / D21 · D24 · D31 · R3). A missing body = both fields missing.
 * A market outside Coupang ignores it (D21). An unknown item key fails the body read → 400.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Items to overwrite and option lines to send on a Coupang [수정 요청] (all optional)")
public class ListingUpdateSelectionRequest {

    @Schema(description = "Item keys to overwrite with the oclyx value · null = the 6 batch items (D9) · [] = none (D11)")
    private List<ListingUpdateItem> overwrite;

    @Schema(description = "Product listing option ids of the option lines to send (D31) · null = every line active now · [] = none")
    private List<Long> addOptions;
}
