package com.airtribe.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponse(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message,
        @JsonProperty("request_id") String requestId
) {
    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(code, message, requestId);
    }
}
