package com.verety.lancamentos.web;

/** Cabeçalho obrigatório ausente/inválido (ex.: {@code Idempotency-Key}) → HTTP 400. */
public class CabecalhoAusenteException extends RuntimeException {

    public CabecalhoAusenteException(String message) {
        super(message);
    }
}
