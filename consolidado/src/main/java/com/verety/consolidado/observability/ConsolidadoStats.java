package com.verety.consolidado.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leituras do estado do read-side ({@code consolidado}, ADR-004/ADR-006) para métricas e health:
 * eventos no ledger ainda não absorvidos pelo rollup, defasagem do watermark e idade do mais antigo.
 * Somente leitura e à prova de falha: erro de banco degrada para 0 (o health nativo já cobre o banco).
 */
@Component
public class ConsolidadoStats {

    private static final String UNROLLED_WHERE =
            "FROM consolidado.processed_event pe "
                    + "WHERE pe.processado_em > (SELECT ultimo_processado_em FROM consolidado.rollup_controle)";

    private final JdbcTemplate jdbc;

    public ConsolidadoStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Eventos no ledger ainda não absorvidos pelo rollup. */
    public long unrolledEvents() {
        return queryLong("SELECT count(*) " + UNROLLED_WHERE);
    }

    /** Defasagem (s) do watermark de rollup, proxy da idade do backlog do consolidado (ADR-009 item 3). */
    public double rollupLagSeconds() {
        return queryDouble("SELECT COALESCE(EXTRACT(EPOCH FROM (now() "
                + "- (SELECT ultimo_processado_em FROM consolidado.rollup_controle))), 0)");
    }

    /** Idade (s) do evento não-absorvido mais antigo (distingue pico saudável de travamento). */
    public double oldestUnrolledAgeSeconds() {
        return queryDouble("SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(pe.processado_em))), 0) "
                + UNROLLED_WHERE);
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
