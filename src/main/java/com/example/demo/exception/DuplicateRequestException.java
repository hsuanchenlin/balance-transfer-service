package com.example.demo.exception;

public class DuplicateRequestException extends RuntimeException {
    public DuplicateRequestException(String requestId) {
        super("Duplicate transfer request: " + requestId);
    }
}
