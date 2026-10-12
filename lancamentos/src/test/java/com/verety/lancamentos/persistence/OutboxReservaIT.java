package com.verety.lancamentos.persistence;

import com.verety.lancamentos.support.AbstractJpaIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reserva do outbox sob concorrência (ADR-003): {@code SELECT ... FOR UPDATE SKIP LOCKED} garante
 * que dois relays NUNCA reservem a MESMA linha {@code PENDING}. Sem SKIP LOCKED, o 2º relay
 * bloquearia ou (pior) republicaria o mesmo evento.
 */
class OutboxReservaIT extends AbstractJpaIT {

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM lancamentos.outbox");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("SKIP LOCKED · 2 relays concorrentes ⇒ reservas DISJUNTAS (nunca a mesma linha)")
    void reservasDisjuntas() throws Exception {
        semearPendentes(2);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        CountDownLatch aTemLock = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);

        CompletableFuture<List<Long>> loteA = CompletableFuture.supplyAsync(() ->
                tx.execute(status -> {
                    List<Long> ids = outbox.reservarLote(10).stream().map(OutboxEvent::getId).toList();
                    aTemLock.countDown();
                    try {
                        liberarA.await(10, TimeUnit.SECONDS); // segura os locks enquanto B tenta
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ids;
                }));

        assertThat(aTemLock.await(10, TimeUnit.SECONDS)).isTrue();

        // B roda com as linhas ainda travadas por A: SKIP LOCKED ⇒ não vê nenhuma.
        List<Long> loteB = tx.execute(status -> outbox.reservarLote(10).stream().map(OutboxEvent::getId).toList());
        liberarA.countDown();

        List<Long> idsA = loteA.get(10, TimeUnit.SECONDS);
        assertThat(idsA).as("A reserva as 2 linhas").hasSize(2);
        assertThat(loteB).as("B não reserva nenhuma (locks pulados, sem duplicar)").isEmpty();
        assertThat(java.util.Collections.disjoint(idsA, loteB)).as("reservas disjuntas").isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("só PENDING e vencido é reservado; PUBLISHED fica de fora")
    void soReservaPendenteVencido() {
        semearPendentes(1);
        jdbc.update("INSERT INTO lancamentos.outbox "
                + "(aggregate_id,event_id,event_type,payload,status,attempts,next_attempt,created_at) "
                + "VALUES (1, ?, 'LancamentoCriado', '{}'::jsonb,'PUBLISHED',0, now(), now())", UUID.randomUUID());

        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<Long> ids = tx.execute(status -> outbox.reservarLote(10).stream().map(OutboxEvent::getId).toList());

        assertThat(ids).as("o PUBLISHED não entra na reserva").hasSize(1);
    }

    private void semearPendentes(int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO lancamentos.outbox "
                    + "(aggregate_id,event_id,event_type,payload,status,attempts,next_attempt,created_at) "
                    + "VALUES (?, ?, 'LancamentoCriado', '{}'::jsonb,'PENDING',0, now(), now())",
                    (long) (i + 1), UUID.randomUUID());
        }
    }
}
