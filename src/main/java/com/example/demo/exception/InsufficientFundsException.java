package com.example.demo.exception;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String userId) {
        super("Insufficient funds: " + userId);
    }
}
