package com.verety.lancamentos.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Representação de {@code lancamento} (openapi.yaml {@code Lancamento}).
 * Dinheiro e status como string; {@code criado_em} ISO-8601.
 */
public record LancamentoResponse(
        @JsonProperty("lancamento_id") String lancamentoId,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("comerciante_id") String comercianteId,
        String tipo,
        String valor,
        String moeda,
        @JsonProperty("data_negocio") LocalDate dataNegocio,
        String status,
        @JsonProperty("criado_em") Instant criadoEm
) {
}
