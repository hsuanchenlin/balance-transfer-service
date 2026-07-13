package com.example.demo.exception;

public class SelfTransferException extends RuntimeException {
    public SelfTransferException(String userId) {
        super("Cannot transfer to self: " + userId);
    }
}
