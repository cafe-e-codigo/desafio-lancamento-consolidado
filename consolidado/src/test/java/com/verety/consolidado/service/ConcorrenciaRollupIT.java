package com.verety.consolidado.service;

import com.verety.common.dto.LancamentoCriado;
import com.verety.consolidado.support.AbstractJpaIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concorrência (contenção da conta quente) contra Postgres 16 real, com transações
 * <b>de verdade</b> ({@code NOT_SUPPORTED}: o teste desliga a tx que o JUnit abriria, então cada
 * chamada do serviço commita, necessário para exercitar locks entre threads).
 *
 * <p>30 eventos DISTINTOS na MESMA conta/dia, em paralelo. O ledger é append-only
 * (INSERTs concorrentes não serializam); o rollup serializa em 1 linha por {@code (comerciante,data)}
 * via {@code SELECT ... FOR UPDATE} + recomputo ABSOLUTO. O oráculo final: δ=0, nenhum lost update.
 */
@Import(ConsolidacaoService.class)
class ConcorrenciaRollupIT extends AbstractJpaIT {

    private static final LocalDate DIA = LocalDate.of(2026, 10, 9);

    @Autowired
    private ConsolidacaoService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("concorrente: N threads no MESMO event_id ⇒ 1 linha, saldo 1×")
    void c1_mesmoEventoConcorrenteUmaSoAplicacao() throws Exception {
        UUID comerciante = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        LancamentoCriado evento = evento(comerciante, eventId, "CREDITO", "77.00");

        List<Long> novos = rodar(16, () -> service.processarEvento(evento).novo() ? 1L : 0L);

        long aplicacoes = novos.stream().filter(n -> n == 1L).count();
        assertThat(aplicacoes).as("exatamente 1 thread vence o INSERT ... DO NOTHING").isEqualTo(1L);
        assertThat(count(eventId, comerciante)).isEqualTo(1L);
        assertThat(saldo(comerciante)).isEqualByComparingTo("77.00");
        limpar(comerciante);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("30 eventos distintos na MESMA conta em paralelo ⇒ δ=0")
    void c9_contaQuenteSemLostUpdate() throws Exception {
        UUID comerciante = UUID.randomUUID();
        List<LancamentoCriado> eventos = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> evento(comerciante, UUID.randomUUID(),
                        i % 3 == 0 ? "DEBITO" : "CREDITO", (i + 1) + ".00"))
                .toList();

        // dispara em paralelo, um evento por tarefa
        ExecutorService pool = Executors.newFixedThreadPool(15);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Object>> futuros = eventos.stream()
                .map(e -> pool.submit(() -> {
                    largada.await();
                    service.processarEvento(e);
                    return (Object) null;
                }))
                .toList();
        largada.countDown();
        for (Future<Object> f : futuros) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        BigDecimal somaDistinta = jdbc.queryForObject(
                "SELECT COALESCE(SUM(valor_signed),0) FROM consolidado.processed_event WHERE comerciante_id = ?",
                BigDecimal.class, comerciante);
        long distintos = jdbc.queryForObject(
                "SELECT count(DISTINCT event_id) FROM consolidado.processed_event WHERE comerciante_id = ?",
                Long.class, comerciante);

        assertThat(distintos).as("30 eventos distintos").isEqualTo(30L);
        assertThat(saldo(comerciante).subtract(somaDistinta))
                .as("δ = saldo_diario − Σ eventos distintos == 0 (sem lost update)").isEqualByComparingTo("0.00");
        limpar(comerciante);
    }

    // -------------------------------------------------------------- helpers

    private List<Long> rodar(int n, Callable<Long> tarefa) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Long>> futuros = java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> pool.submit(() -> {
                    largada.await();
                    return tarefa.call();
                }))
                .toList();
        largada.countDown();
        List<Long> out = new java.util.ArrayList<>();
        for (Future<Long> f : futuros) {
            out.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return out;
    }

    private LancamentoCriado evento(UUID comerciante, UUID eventId, String tipo, String valor) {
        return new LancamentoCriado(eventId, 1L, comerciante, tipo,
                new BigDecimal(valor), "BRL", DIA, UUID.randomUUID());
    }

    private BigDecimal saldo(UUID comerciante) {
        return jdbc.queryForObject(
                "SELECT valor FROM consolidado.saldo_diario WHERE comerciante_id = ? AND data = ?",
                BigDecimal.class, comerciante, DIA);
    }

    private long count(UUID eventId, UUID comerciante) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM consolidado.processed_event WHERE event_id = ? AND comerciante_id = ?",
                Long.class, eventId, comerciante);
    }

    private void limpar(UUID comerciante) {
        jdbc.update("DELETE FROM consolidado.processed_event WHERE comerciante_id = ?", comerciante);
        jdbc.update("DELETE FROM consolidado.saldo_diario WHERE comerciante_id = ?", comerciante);
    }
}
