package com.verety.lancamentos.service;

/** Payload válido em formato mas inválido no domínio (tipo/valor/moeda/data) → HTTP 422. */
public class DominioInvalidoException extends RuntimeException {

    public DominioInvalidoException(String message) {
        super(message);
    }
}
