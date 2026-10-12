package com.verety.lancamentos.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leituras de estado do Transactional Outbox (esquema {@code lancamentos}, ADR-003) para métricas e
 * health. Somente leitura e à prova de falha: qualquer erro de banco degrada para 0 em vez de
 * derrubar o scrape/health (a indisponibilidade do banco já é reportada pelo health nativo).
 */
@Component
public class OutboxStats {

    private final JdbcTemplate jdbc;

    public OutboxStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Defasagem (s) do evento PENDING mais antigo: o "desacoplamento" medível (ADR-009 item 2). */
    public double oldestPendingAgeSeconds() {
        return queryDouble("SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) "
                + "FROM lancamentos.outbox WHERE status = 'PENDING'");
    }

    public long pending() {
        return queryLong("SELECT count(*) FROM lancamentos.outbox WHERE status = 'PENDING'");
    }

    /** Estado terminal (poison-outbox): ADR-009 item 2. */
    public long terminal() {
        return queryLong("SELECT count(*) FROM lancamentos.outbox WHERE status = 'FAILED'");
    }

    private double queryDouble(String sql) {
        try {
            Double value = jdbc.queryForObject(sql, Double.class);
            return value == null ? 0d : value;
        } catch (Exception e) {
            return 0d;
        }
    }

    private long queryLong(String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? 0L : value;
        } catch (Exception e) {
            return 0L;
        }
    }
}
