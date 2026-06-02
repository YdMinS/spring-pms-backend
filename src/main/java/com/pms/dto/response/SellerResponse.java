package com.pms.dto.response;

import java.time.LocalDateTime;

public record SellerResponse(
    Long id,
    String sellerName,
    String businessRegistration,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
