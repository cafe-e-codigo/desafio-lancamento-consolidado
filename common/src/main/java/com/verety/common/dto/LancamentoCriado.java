package com.verety.common.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Evento canônico publicado pelo write-side e consumido pelo read-side
 * ({@code LancamentoCriado}, ADR-003/ADR-005).
 *
 * <p>Tipos alinhados ao <b>DDL canônico</b> ({@code docs/schema.sql}). O DDL não é alterado e
 * o código espelha exatamente estas colunas:
 * <ul>
 *   <li>{@code eventId}: {@link UUID} = {@code outbox.event_id} = {@code lancamento.transaction_id}
 *       (Idempotency-Key → UUIDv5, ADR-005). É a chave de dedup do consumidor.</li>
 *   <li>{@code lancamentoId}: {@code long} = {@code lancamento.id} ({@code bigint GENERATED ALWAYS
 *       AS IDENTITY}). Referência <b>lógica</b> ao agregado; NÃO é chave de idempotência (essa é
 *       {@code eventId}). Por isso o relay pode reconstruir o payload sem o id físico.</li>
 *   <li>{@code comercianteId}: {@link UUID} = {@code lancamento.comerciante_id}.</li>
 *   <li>{@code valor}: {@link BigDecimal} ↔ {@code numeric(18,2)}; nunca float (RF02).</li>
 *   <li>{@code dataNegocio}: data de negócio (event-time, ADR-012); coluna {@code data} no DDL/ER.</li>
 * </ul>
 */
public record LancamentoCriado(
        UUID eventId,
        long lancamentoId,
        UUID comercianteId,
        String tipo,
        BigDecimal valor,
        String moeda,
        LocalDate dataNegocio,
        UUID correlationId
) {
}
