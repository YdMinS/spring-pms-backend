package com.pms.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Common API response wrapper")
public class ResponseDTO<T> {

    public enum ResponseStatus {
        SUCCESS, FAILURE
    }

    @Schema(description = "Response status", example = "SUCCESS", allowableValues = {"SUCCESS", "FAILURE"})
    private ResponseStatus status;

    @Schema(description = "Response message", example = "User registered successfully")
    private String message;

    @Schema(description = "Response data (null on failure)")
    private T data;

    @Schema(description = "Response timestamp", example = "2024-01-01T00:00:00Z")
    private String timestamp;

    public static <T> ResponseDTO<T> success(T data) {
        return ResponseDTO.<T>builder()
                .status(ResponseStatus.SUCCESS)
                .message("Success")
                .data(data)
                .timestamp(Instant.now().toString())
                .build();
    }

    public static <T> ResponseDTO<T> success(String message, T data) {
        return ResponseDTO.<T>builder()
                .status(ResponseStatus.SUCCESS)
                .message(message)
                .data(data)
                .timestamp(Instant.now().toString())
                .build();
    }

    public static <T> ResponseDTO<T> failure(String message) {
        return ResponseDTO.<T>builder()
                .status(ResponseStatus.FAILURE)
                .message(message)
                .timestamp(Instant.now().toString())
                .build();
    }
}
