package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Result of the per-channel [마스터 옵션명 반영] (FEATURE_2609_74 / D15): this channel's linked options get
 * their names reset to the master option's name, and {@code optionNameSource} back to {@code AUTO}.
 *
 * <p>Name-locked options (D32·D33 — on the market without an option id and the listing is not REJECTED) are
 * skipped and listed by their current channel name in {@code skippedAwaitingId}.</p>
 */
@Getter
@Builder
@Schema(description = "Per-channel option-name reset result")
public class ChannelApplyOptionNamesResponse {

    @Schema(description = "Options whose name or source actually changed and were saved", example = "2")
    private int updatedOptions;

    @Schema(description = "Current channel names of options skipped because their name is locked "
            + "(no option id yet and the listing is not REJECTED). Empty list when none")
    private List<String> skippedAwaitingId;
}
