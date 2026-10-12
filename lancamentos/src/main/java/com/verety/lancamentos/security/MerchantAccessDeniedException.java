package com.verety.lancamentos.security;

/**
 * Violação de ownership por recurso (BOLA, OWASP API1), item 2 do ADR-008:
 * o {@code merchant_id} do token difere do recurso. Mapeada a 403/404 (não vaza existência)
 * pelo {@link SecurityProblemAdvice}; o 404 é o default para não distinguir "existe"/"não existe".
 */
public class MerchantAccessDeniedException extends RuntimeException {

    public MerchantAccessDeniedException(String message) {
        super(message);
    }
}
