package com.pms.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Save request of the company's 11st photo lookup key (FEATURE_2611_11 / D39). A blank or missing {@code apiKey}
 * clears the stored key.
 */
public record ElevenstPhotoKeyRequest(
        @Size(max = 100) String apiKey) {
}
