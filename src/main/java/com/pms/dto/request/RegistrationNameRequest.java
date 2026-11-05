package com.pms.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 「등록상품명」 save body (FEATURE_2611_11 / D5). A typed name is stored for the listing (trimmed); a blank or missing
 * {@code registrationName} clears it — [자동 이름으로 되돌리기] (the automatic name is used again). Capped at 255
 * ({@code product_listing.registration_name}); Coupang itself takes 100 and the push cuts the rest (93).
 */
public record RegistrationNameRequest(
        @Size(max = 255) String registrationName) {
}
