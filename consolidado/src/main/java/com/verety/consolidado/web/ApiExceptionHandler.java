package com.verety.consolidado.web;

import com.verety.common.error.ErroPadrao;
import com.verety.consolidado.service.RecursoNaoEncontradoException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Tratamento de erros do read-side → RFC 9457 {@code application/problem+json} (ADR-008).
 * {@code 400} para data/parâmetro malformado; {@code 404} quando não há projeção para a data.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<ErroPadrao> naoEncontrado(RecursoNaoEncontradoException e) {
        return problema(ErroPadrao.naoEncontrado(e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErroPadrao> requisicaoInvalida(Exception e) {
        return problema(ErroPadrao.validacao("Parâmetro inválido na requisição."));
    }

    private static ResponseEntity<ErroPadrao> problema(ErroPadrao corpo) {
        return ResponseEntity.status(corpo.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(corpo);
    }
}
