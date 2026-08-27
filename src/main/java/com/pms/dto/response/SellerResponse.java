package com.pms.dto.response;

import java.time.LocalDateTime;

public record SellerResponse(
    Long id,
    String sellerName,
    String businessRegistration,
    // "옵션확인" suffix seller-level override (69): null = inherit. Prefill source for the front's replace PUT.
    Boolean optionCheckSuffixEnabled,
    String optionCheckSuffix,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
