package com.example.demo.model;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CreateUserRequest(
        @NotBlank String userId,
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal initialBalance) {
}
