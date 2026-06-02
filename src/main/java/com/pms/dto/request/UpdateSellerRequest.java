package com.pms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSellerRequest(
    @NotBlank(message = "판매자명은 필수입니다")
    @Size(min = 1, max = 255, message = "판매자명은 1-255자여야 합니다")
    String sellerName,

    @NotBlank(message = "사업자등록번호는 필수입니다")
    @Size(min = 1, max = 50, message = "사업자등록번호는 1-50자여야 합니다")
    String businessRegistration
) {}
