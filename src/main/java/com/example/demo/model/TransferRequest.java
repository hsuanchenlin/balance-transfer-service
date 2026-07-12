package com.example.demo.model;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TransferRequest(
        @NotBlank String fromUserId,
        @NotBlank String toUserId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        String requestId) {
}
