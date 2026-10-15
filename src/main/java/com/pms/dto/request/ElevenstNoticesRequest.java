package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 11st channel notice defaults (FEATURE_2610_16 / D7), replaced whole. A null or blank text is stored as null
 * (D16); each text is limited to 4000 EUC-KR bytes by the service (D8).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "11st A/S and return/exchange notice defaults of one 11st sales channel")
public class ElevenstNoticesRequest {

    private String asNotice;

    private String returnExchangeNotice;
}
