package com.example.demo.exception;

public class TransferNotFoundException extends RuntimeException {
    public TransferNotFoundException(long transferId) {
        super("Transfer not found: " + transferId);
    }
}
