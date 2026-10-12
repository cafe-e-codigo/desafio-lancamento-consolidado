package com.verety.lancamentos.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Métricas custom do outbox (ADR-009 item 2), publicadas pelo Micrometer via actuator, sem APM pago
 * e sem nó novo:
 * <ul>
 *   <li>{@code verety.outbox.oldest_age_seconds}: defasagem do PENDING mais antigo (RNF desacoplamento);</li>
 *   <li>{@code verety.outbox.pending} / {@code verety.outbox.terminal}: volume e estado terminal (poison).</li>
 * </ul>
 */
@Component
public class OutboxMetrics implements MeterBinder {

    private final OutboxStats stats;

    public OutboxMetrics(OutboxStats stats) {
        this.stats = stats;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("verety.outbox.oldest_age_seconds", stats, OutboxStats::oldestPendingAgeSeconds)
                .description("Defasagem em segundos do evento PENDING mais antigo no outbox")
                .register(registry);
        Gauge.builder("verety.outbox.pending", stats, s -> (double) s.pending())
                .description("Eventos no outbox aguardando publicação")
                .register(registry);
        Gauge.builder("verety.outbox.terminal", stats, s -> (double) s.terminal())
                .description("Eventos no outbox em estado terminal (FAILED)")
                .register(registry);
    }
}
