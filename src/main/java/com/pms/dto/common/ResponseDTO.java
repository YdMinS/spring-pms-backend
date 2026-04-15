package com.pms.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class ResponseDTO<T> {

    public enum ResponseStatus {
        SUCCESS, FAILURE
    }

    private ResponseStatus status;
    private String message;
    private T data;
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
