package com.verety.common.error;

/**
 * Erro padronizado: RFC 9457 (Problem Details for HTTP APIs), {@code application/problem+json}
 * (openapi.yaml, ADR-008).
 *
 * <p>Campos: {@code type} (URI do catálogo de problemas), {@code title}, {@code status},
 * {@code detail}, {@code instance}. Extensões são permitidas pelo contrato.
 */
public record ErroPadrao(
        String type,
        String title,
        int status,
        String detail,
        String instance
) {

    /** Namespace-base das URIs de problema do Verety. */
    public static final String PROBLEM_NS = "https://verety.example/problems/";

    public static ErroPadrao of(String slug, String title, int status, String detail) {
        return new ErroPadrao(PROBLEM_NS + slug, title, status, detail, null);
    }

    public static ErroPadrao validacao(String detail) {
        return of("validacao", "Requisição inválida", 400, detail);
    }

    public static ErroPadrao idempotencyKeyReuse(String detail) {
        return of("idempotency-key-reuse", "Idempotency-Key reuse", 409, detail);
    }

    public static ErroPadrao regraDeNegocio(String detail) {
        return of("regra-de-negocio", "Payload válido em formato, inválido no domínio", 422, detail);
    }

    public static ErroPadrao naoEncontrado(String detail) {
        return of("nao-encontrado", "Sem lançamentos para a data", 404, detail);
    }

    public static ErroPadrao indisponivel(String detail) {
        return of("indisponivel", "Serviço indisponível", 503, detail);
    }
}
