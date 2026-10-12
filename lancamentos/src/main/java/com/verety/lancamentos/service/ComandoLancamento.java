package com.verety.lancamentos.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Comando de domínio para criação de um lançamento. Desacopla a camada web da de serviço.
 *
 * <p>{@code comercianteId} já resolvido para {@link UUID} (a coluna é {@code uuid} no DDL canônico).
 */
public record ComandoLancamento(
        UUID comercianteId,
        String tipo,
        String valor,
        String moeda,
        LocalDate dataNegocio
) {
}
