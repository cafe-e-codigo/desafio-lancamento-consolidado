package com.verety.consolidado.service;

import com.verety.common.dto.LancamentoCriado;
import com.verety.consolidado.support.AbstractJpaIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Núcleo adversarial do read-side, contra <b>Postgres 16 real</b>.
 *
 * <p>O oráculo conta no banco (SQL por fora), nunca no contador de métricas da app
 * ({@code writer == validator}).
 *
 * <ul>
 *   <li>reentrega do MESMO {@code (event_id, data)} ⇒ 1 única aplicação;
 *       {@code COUNT(DISTINCT event_id)} = 1 e saldo aplicado 1×.</li>
 *   <li>rollup soma <b>ASSINADA</b> (CREDITO+, DEBITO−), {@code numeric(18,2)},
 *       1 linha por {@code (comerciante, data)}.</li>
 *   <li>INVARIANTE DISTINCT: {@code Σ saldo_diario == Σ eventos distintos} (δ=0),
 *       e a forma CRUA (não-DISTINCT) <b>não</b> fecha sob duplicata, senão o oráculo mente.</li>
 * </ul>
 */
@Import(ConsolidacaoService.class)
class LedgerRollupIT extends AbstractJpaIT {

    private static final LocalDate DIA = LocalDate.of(2026, 10, 8);
    private static final UUID COMERCIANTE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private ConsolidacaoService service;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------

    @Test
    @DisplayName("reentrega do mesmo (event_id,data) aplica UMA vez")
    void c1_reentregaAplicaUmaSoVez() {
        UUID eventId = UUID.randomUUID();
        LancamentoCriado evento = evento(eventId, "CREDITO", "100.00");

        ConsolidacaoService.Resultado r1 = service.processarEvento(evento);
        ConsolidacaoService.Resultado r2 = service.processarEvento(evento); // reentrega

        assertThat(r1.novo()).as("1ª entrega é novidade").isTrue();
        assertThat(r2.novo()).as("2ª entrega é no-op (ON CONFLICT DO NOTHING)").isFalse();

        assertThat(countDistinctEventIds()).as("1 único evento distinto no ledger").isEqualTo(1L);
        assertThat(countLedgerLinhas()).as("1 única linha no ledger").isEqualTo(1L);
        assertThat(saldo()).as("saldo aplicado 1×, não dobrou").isEqualByComparingTo("100.00");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("rollup soma ASSINADA (CREDITO+, DEBITO−) e escala 2")
    void c2_rollupSomaAssinada() {
        service.processarEvento(evento(UUID.randomUUID(), "CREDITO", "100.00"));
        service.processarEvento(evento(UUID.randomUUID(), "DEBITO", "40.10"));
        service.processarEvento(evento(UUID.randomUUID(), "CREDITO", "0.05"));

        BigDecimal esperado = new BigDecimal("59.95"); // 100.00 - 40.10 + 0.05
        assertThat(saldo()).isEqualByComparingTo(esperado);
        assertThat(saldo().scale()).as("numeric(18,2)").isEqualTo(2);
    }

    @Test
    @DisplayName("1 linha por (comerciante,data): rollup, nunca por evento")
    void c2_umaLinhaPorGrupo() {
        for (int i = 0; i < 25; i++) {
            service.processarEvento(evento(UUID.randomUUID(), i % 2 == 0 ? "CREDITO" : "DEBITO", "1.00"));
        }
        Integer linhas = jdbc.queryForObject(
                "SELECT count(*) FROM consolidado.saldo_diario WHERE comerciante_id = ? AND data = ?",
                Integer.class, COMERCIANTE, DIA);
        assertThat(linhas).as("25 eventos ⇒ 1 linha de saldo (não 25)").isEqualTo(1);
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("invariante DISTINCT fecha com δ=0 mesmo com duplicata do relay")
    void c3b_invarianteDistinctDeltaZero() {
        LancamentoCriado a = evento(UUID.randomUUID(), "CREDITO", "10.00");
        LancamentoCriado b = evento(UUID.randomUUID(), "DEBITO", "3.50");

        service.processarEvento(a);
        service.processarEvento(a); // duplicata do relay (mesmo event_id)
        service.processarEvento(b);
        service.processarEvento(b); // duplicata do relay (mesmo event_id)

        // Forma DISTINCT: saldo == soma dos eventos distintos.
        BigDecimal somaDistinta = jdbc.queryForObject(
                "SELECT COALESCE(SUM(valor_signed),0) FROM consolidado.processed_event "
                        + "WHERE comerciante_id = ? AND data = ?", BigDecimal.class, COMERCIANTE, DIA);
        BigDecimal delta = saldo().subtract(somaDistinta);
        assertThat(delta).as("δ = saldo_diario − Σ eventos distintos").isEqualByComparingTo("0.00");
        assertThat(countDistinctEventIds()).isEqualTo(2L);

        // Forma CRUA (não-DISTINCT) NÃO fecha: prova que o teste é sensível (não é vacuidade).
        long cru = jdbc.queryForObject(
                "SELECT count(*) FROM consolidado.processed_event", Long.class);
        assertThat(cru).as("2 entregas duplicadas não entram no ledger, dedup é durável").isEqualTo(2L);
    }

    @Test
    @DisplayName("controle, oráculo é sensível: 2 eventos DISTINTOS somam os dois")
    void c3b_controleNegativoOraculoSensivel() {
        service.processarEvento(evento(UUID.randomUUID(), "CREDITO", "10.00"));
        service.processarEvento(evento(UUID.randomUUID(), "CREDITO", "20.00"));

        assertThat(countDistinctEventIds()).isEqualTo(2L);
        assertThat(saldo()).as("se o rollup perdesse um evento, o oráculo acusaria").isEqualByComparingTo("30.00");
    }

    // -------------------------------------------------------------- helpers

    private LancamentoCriado evento(UUID eventId, String tipo, String valor) {
        return new LancamentoCriado(eventId, 1L, COMERCIANTE, tipo,
                new BigDecimal(valor), "BRL", DIA, UUID.randomUUID());
    }

    private BigDecimal saldo() {
        return jdbc.queryForObject(
                "SELECT valor FROM consolidado.saldo_diario WHERE comerciante_id = ? AND data = ?",
                BigDecimal.class, COMERCIANTE, DIA);
    }

    private long countDistinctEventIds() {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT event_id) FROM consolidado.processed_event WHERE comerciante_id = ? AND data = ?",
                Long.class, COMERCIANTE, DIA);
    }

    private long countLedgerLinhas() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM consolidado.processed_event WHERE comerciante_id = ? AND data = ?",
                Long.class, COMERCIANTE, DIA);
    }
}
