package com.verety.consolidado.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;

/** Representação de {@code Consolidado} (openapi.yaml). Dinheiro como string decimal. */
public record ConsolidadoResponse(
        @JsonProperty("comerciante_id") String comercianteId,
        LocalDate data,
        String saldo,
        String moeda,
        @JsonProperty("atualizado_em") Instant atualizadoEm,
        String status
) {
}
