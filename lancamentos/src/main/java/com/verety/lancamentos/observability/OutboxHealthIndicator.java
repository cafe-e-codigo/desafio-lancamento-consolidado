package com.verety.lancamentos.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health check do outbox (ADR-009): DOWN se houver evento em estado terminal (poison-outbox) ou se a
 * defasagem do PENDING mais antigo exceder o limiar configurado. Limiar com responsável, como exige o ADR.
 */
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxStats stats;
    private final double lagWarnSeconds;
    private final boolean terminalDown;

    public OutboxHealthIndicator(
            OutboxStats stats,
            @Value("${verety.observability.outbox.lag-warn-seconds:300}") double lagWarnSeconds,
            @Value("${verety.observability.outbox.terminal-down:true}") boolean terminalDown) {
        this.stats = stats;
        this.lagWarnSeconds = lagWarnSeconds;
        this.terminalDown = terminalDown;
    }

    @Override
    public Health health() {
        try {
            long pending = stats.pending();
            long terminal = stats.terminal();
            double age = stats.oldestPendingAgeSeconds();
            boolean down = (terminalDown && terminal > 0) || age > lagWarnSeconds;
            return (down ? Health.down() : Health.up())
                    .withDetail("pending", pending)
                    .withDetail("terminal", terminal)
                    .withDetail("oldest_pending_age_seconds", age)
                    .withDetail("lag_warn_threshold_seconds", lagWarnSeconds)
                    .build();
        } catch (Exception e) {
            return Health.status("UNKNOWN").withException(e).build();
        }
    }
}
