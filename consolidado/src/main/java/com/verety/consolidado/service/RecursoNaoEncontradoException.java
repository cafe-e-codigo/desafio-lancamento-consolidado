package com.verety.consolidado.service;

/** Sem projeção de saldo para a data pedida (saldo zero não é materializado) → HTTP 404. */
public class RecursoNaoEncontradoException extends RuntimeException {

    public RecursoNaoEncontradoException(String message) {
        super(message);
    }
}
