package com.verety.consolidado.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Invalidação do cache de leitura do consolidado. Implementado por {@link SaldoQueryService}
 * (Redis) e consumido por {@link ConsolidacaoService} <b>após o commit do rollup</b>.
 *
 * <p>Sem isso, a entrada de cache ficaria presa ao TTL e a leitura poderia servir um saldo
 * velho por até 30 s. É opcional no grafo: em contextos sem Redis (fatia JPA) a evict fica inativa.
 */
public interface SaldoCacheEvictor {

    /** Remove a entrada {@code (comerciante, data)} do cache. */
    void evict(UUID comercianteId, LocalDate data);
}
