package com.verety.consolidado.security;

import com.verety.common.error.ErroPadrao;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Advice de segurança dos controllers do eixo Backend (não reescreve o corpo deles): traduz falhas de
 * autorização para RFC 9457. BOLA &rarr; 404 (não vaza existência, ADR-008 item 2); falta de
 * autoridade &rarr; 403 (BFLA).
 */
@RestControllerAdvice
public class SecurityProblemAdvice {

    @ExceptionHandler(MerchantAccessDeniedException.class)
    public ResponseEntity<ErroPadrao> onBola(MerchantAccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ErroPadrao.of("nao-encontrado", "Recurso não encontrado", 404,
                        "O recurso solicitado não está disponível para este chamador."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErroPadrao> onForbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ErroPadrao.of("acesso-negado", "Acesso negado", 403,
                        "O chamador não possui escopo suficiente para esta operação."));
    }
}
