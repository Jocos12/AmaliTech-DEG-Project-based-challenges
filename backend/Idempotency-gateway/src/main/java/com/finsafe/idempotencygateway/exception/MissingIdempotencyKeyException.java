package com.finsafe.idempotencygateway.exception;

public class MissingIdempotencyKeyException extends RuntimeException {

    public static final String MESSAGE = "Idempotency-Key header is required.";

    public MissingIdempotencyKeyException() {
        super(MESSAGE);
    }
}
