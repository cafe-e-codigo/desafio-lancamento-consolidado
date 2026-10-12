package com.verety.consolidado.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health check do read-side (ADR-009 item 3): DOWN quando a defasagem de rollup (ou a idade do
 * evento mais antigo não-absorvido) excede o limiar configurado: o "travamento" que um simples
 * {@code MessageCount} não distingue de um pico saudável. Limiar com responsável.
 */
@Component("consolidado")
public class ConsolidadoHealthIndicator implements HealthIndicator {

    private final ConsolidadoStats stats;
    private final double lagThresholdSeconds;

    public ConsolidadoHealthIndicator(
            ConsolidadoStats stats,
            @Value("${verety.observability.consolidado.lag-warn-seconds:300}") double lagThresholdSeconds) {
        this.stats = stats;
        this.lagThresholdSeconds = lagThresholdSeconds;
    }

    @Override
    public Health health() {
        try {
            long unrolled = stats.unrolledEvents();
            double lag = stats.rollupLagSeconds();
            double oldest = stats.oldestUnrolledAgeSeconds();
            boolean down = lag > lagThresholdSeconds || oldest > lagThresholdSeconds;
            return (down ? Health.down() : Health.up())
                    .withDetail("unrolled_events", unrolled)
                    .withDetail("rollup_lag_seconds", lag)
                    .withDetail("oldest_unrolled_age_seconds", oldest)
                    .withDetail("lag_warn_threshold_seconds", lagThresholdSeconds)
                    .build();
        } catch (Exception e) {
            return Health.status("UNKNOWN").withException(e).build();
        }
    }
}
