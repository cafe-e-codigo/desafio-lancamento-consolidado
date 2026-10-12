package com.verety.consolidado.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Métricas do read-side (ADR-009 itens 3 e 5) via Micrometer/actuator, sem APM pago, sem nó novo:
 * <ul>
 *   <li>{@code verety.consolidado.rollup_lag_seconds}: defasagem do watermark de rollup;</li>
 *   <li>{@code verety.consolidado.unrolled_events} / {@code oldest_unrolled_age_seconds}: backlog;</li>
 *   <li>{@code verety.cache.redis.hits}/{@code misses}/{@code hit_ratio}: hit/miss real do Redis.</li>
 * </ul>
 */
@Component
public class ConsolidadoMetrics implements MeterBinder {

    private final ConsolidadoStats stats;
    private final CacheStats cache;

    public ConsolidadoMetrics(ConsolidadoStats stats, CacheStats cache) {
        this.stats = stats;
        this.cache = cache;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("verety.consolidado.rollup_lag_seconds", stats, ConsolidadoStats::rollupLagSeconds)
                .description("Defasagem em segundos do watermark de rollup do consolidado")
                .register(registry);
        Gauge.builder("verety.consolidado.unrolled_events", stats, s -> (double) s.unrolledEvents())
                .description("Eventos no ledger ainda não absorvidos pelo rollup")
                .register(registry);
        Gauge.builder("verety.consolidado.oldest_unrolled_age_seconds", stats,
                        ConsolidadoStats::oldestUnrolledAgeSeconds)
                .description("Idade em segundos do evento não-absorvido mais antigo")
                .register(registry);
        Gauge.builder("verety.cache.redis.hits", cache, s -> (double) s.hits())
                .description("keyspace_hits do Redis (cache read-through, ADR-006)")
                .register(registry);
        Gauge.builder("verety.cache.redis.misses", cache, s -> (double) s.misses())
                .description("keyspace_misses do Redis (cache read-through, ADR-006)")
                .register(registry);
        Gauge.builder("verety.cache.redis.hit_ratio", cache, CacheStats::hitRatio)
                .description("hits / (hits + misses) do Redis; -1 quando não há acessos")
                .register(registry);
        Gauge.builder("verety.cache.redis.evicted", cache, s -> (double) s.evicted())
                .description("Chaves evictadas (proxy de pressão de memória/eviction, ADR-006)")
                .register(registry);
    }
}
