package com.airtribe.wallet.dto;

import jakarta.validation.constraints.NotBlank;

public record StatementAskRequest(
        @NotBlank(message = "Question cannot be blank") String question
) {}
