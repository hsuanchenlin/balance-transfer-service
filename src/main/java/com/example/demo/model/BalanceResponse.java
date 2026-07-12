package com.example.demo.model;

import java.math.BigDecimal;

public record BalanceResponse(String userId, BigDecimal balance) {
}
