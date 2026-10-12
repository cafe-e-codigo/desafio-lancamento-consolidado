package com.verety.lancamentos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verety.lancamentos.persistence.LancamentoRepository;
import com.verety.lancamentos.persistence.OutboxRepository;
import com.verety.lancamentos.support.AbstractJpaIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transactional Outbox (ADR-003) e idempotência de origem (ADR-005): contra Postgres 16 real.
 *
 * <ul>
 *   <li><b>Atomicidade:</b> se a tx falha, NEM {@code lancamento} NEM {@code outbox} persistem
 *       (o dual-write é uma coisa só). Prova o "broker fora do caminho, 201 no COMMIT".</li>
 *   <li><b>Idempotência de borda:</b> mesma key + mesmo corpo ⇒ 1 lançamento + 1 outbox;
 *       mesma key + corpo diferente ⇒ {@link IdempotencyConflictException} (409).</li>
 * </ul>
 */
@Import({LancamentoService.class, LancamentoServiceIT.Config.class})
class LancamentoServiceIT extends AbstractJpaIT {

    private static final LocalDate DIA = LocalDate.of(2026, 10, 8);

    @Autowired
    private LancamentoService service;

    @Autowired
    private LancamentoRepository lancamentos;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM lancamentos.outbox");
        jdbc.update("DELETE FROM lancamentos.lancamento");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Outbox · sucesso grava lancamento E outbox na mesma tx")
    void sucessoGravaOsDois() {
        LancamentoService.Criacao c = service.criar("key-ok",
                new ComandoLancamento(UUID.randomUUID(), "CREDITO", "10.00", "BRL", DIA));

        assertThat(c.novo()).isTrue();
        assertThat(countLancamentos()).isEqualTo(1L);
        assertThat(countOutbox()).isEqualTo(1L);
        assertThat(outboxPendente()).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Outbox · rollback ⇒ NEM lancamento NEM outbox (atomicidade)")
    void rollbackNaoDeixaNenhumDosDois() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.executeWithoutResult(status -> {
                service.criar("key-falha",
                        new ComandoLancamento(UUID.randomUUID(), "CREDITO", "10.00", "BRL", DIA));
                status.setRollbackOnly(); // simula falha após os dois INSERTs
            });
        } catch (RuntimeException ignored) {
            // rollback
        }

        assertThat(countLancamentos()).as("lancamento não vazou").isZero();
        assertThat(countOutbox()).as("outbox não vazou (sem evento órfão)").isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("mesma Idempotency-Key + mesmo corpo ⇒ 1 lançamento, replay")
    void mesmaKeyMesmoCorpoEhReplay() {
        UUID comerciante = UUID.randomUUID();
        ComandoLancamento cmd = new ComandoLancamento(comerciante, "CREDITO", "25.00", "BRL", DIA);

        LancamentoService.Criacao c1 = service.criar("key-replay", cmd);
        LancamentoService.Criacao c2 = service.criar("key-replay", cmd);

        assertThat(c1.novo()).isTrue();
        assertThat(c2.novo()).as("replay não cria novo efeito").isFalse();
        assertThat(countLancamentos()).isEqualTo(1L);
        assertThat(countOutbox()).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("mesma Idempotency-Key + corpo DIFERENTE ⇒ 409")
    void mesmaKeyCorpoDiferenteConflita() {
        UUID comerciante = UUID.randomUUID();
        service.criar("key-conflito", new ComandoLancamento(comerciante, "CREDITO", "25.00", "BRL", DIA));

        assertThatThrownBy(() -> service.criar("key-conflito",
                new ComandoLancamento(comerciante, "CREDITO", "99.00", "BRL", DIA)))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(countLancamentos()).as("o corpo divergente NÃO cria 2º lançamento").isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("event_id do lançamento == UUIDv5(Idempotency-Key)")
    void eventIdDeterministico() {
        LancamentoService.Criacao c = service.criar("key-uuid5",
                new ComandoLancamento(UUID.randomUUID(), "DEBITO", "1.00", "BRL", DIA));

        assertThat(c.lancamento().getTransactionId())
                .isEqualTo(Uuid5.of(Uuid5.NS_VERETY_LANCAMENTO, "key-uuid5"));
        assertThat(c.lancamento().getValor()).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    private long countLancamentos() {
        return jdbc.queryForObject("SELECT count(*) FROM lancamentos.lancamento", Long.class);
    }

    private long countOutbox() {
        return jdbc.queryForObject("SELECT count(*) FROM lancamentos.outbox", Long.class);
    }

    private long outboxPendente() {
        return jdbc.queryForObject("SELECT count(*) FROM lancamentos.outbox WHERE status = 'PENDING'", Long.class);
    }
}
