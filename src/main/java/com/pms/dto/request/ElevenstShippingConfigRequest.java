package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 11st channel shipping settings upsert (FEATURE_2610_13 / D84): {@code elevenst.*} key → value, replaced whole. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "11st shipping settings of one sales channel (elevenst.* keys; unknown keys dropped)")
public class ElevenstShippingConfigRequest {

    @NotNull(message = "settings is required")
    private Map<String, String> settings;
}
