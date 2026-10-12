package com.verety.lancamentos.service;

/** Reuso da {@code Idempotency-Key} com corpo diferente (ADR-005) → HTTP 409. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
