package com.verety.lancamentos.web;

import com.verety.common.error.ErroPadrao;
import com.verety.lancamentos.service.DominioInvalidoException;
import com.verety.lancamentos.service.IdempotencyConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratamento de erros do write-side → RFC 9457 {@code application/problem+json} (ADR-008).
 *
 * <p>Contrato: {@code 400} (header/parse), {@code 409} (reuso de Idempotency-Key),
 * {@code 422} (domínio). O POST NÃO responde {@code 503} por indisponibilidade do
 * consolidado/broker (RNF#1): o broker está fora do caminho da request.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CabecalhoAusenteException.class)
    public ResponseEntity<ErroPadrao> cabecalhoAusente(CabecalhoAusenteException e) {
        return problema(ErroPadrao.validacao(e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErroPadrao> requisicaoInvalida(Exception e) {
        return problema(ErroPadrao.validacao(mensagem(e)));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErroPadrao> conflito(IdempotencyConflictException e) {
        return problema(ErroPadrao.idempotencyKeyReuse(e.getMessage()));
    }

    @ExceptionHandler(DominioInvalidoException.class)
    public ResponseEntity<ErroPadrao> dominio(DominioInvalidoException e) {
        return problema(ErroPadrao.regraDeNegocio(e.getMessage()));
    }

    /**
     * Corrida de idempotência: o UNIQUE {@code (comerciante_id, data, transaction_id)} é o backstop.
     * Duas requests simultâneas com a mesma key → uma viola o UNIQUE; trate como reuso (409).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErroPadrao> integridade(DataIntegrityViolationException e) {
        return problema(ErroPadrao.idempotencyKeyReuse(
                "Lançamento já registrado para esta Idempotency-Key."));
    }

    private static ResponseEntity<ErroPadrao> problema(ErroPadrao corpo) {
        return ResponseEntity.status(corpo.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(corpo);
    }

    private static String mensagem(Exception e) {
        if (e instanceof MethodArgumentNotValidException manv && manv.getBindingResult().hasFieldErrors()) {
            var erro = manv.getBindingResult().getFieldErrors().get(0);
            return erro.getField() + ": " + erro.getDefaultMessage();
        }
        return "Requisição malformada.";
    }
}
