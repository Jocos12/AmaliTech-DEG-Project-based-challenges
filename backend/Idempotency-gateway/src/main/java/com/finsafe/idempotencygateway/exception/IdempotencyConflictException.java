package com.finsafe.idempotencygateway.exception;

public class IdempotencyConflictException extends RuntimeException {

    public static final String MESSAGE = "Idempotency key already used for a different request body.";

    public IdempotencyConflictException() {
        super(MESSAGE);
    }
}
