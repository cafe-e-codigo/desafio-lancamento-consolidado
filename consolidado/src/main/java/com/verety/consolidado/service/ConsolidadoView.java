package com.verety.consolidado.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Projeção de leitura do saldo diário (openapi.yaml {@code Consolidado}). */
public record ConsolidadoView(
        UUID comercianteId,
        LocalDate data,
        BigDecimal saldo,
        String moeda,
        Instant atualizadoEm,
        String status
) {
}
