package com.verety.lancamentos.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório da outbox (ADR-003). O relay ({@code OutboxRelay})
 * reserva um lote com {@code FOR UPDATE SKIP LOCKED} e marca o desfecho.
 *
 * <p>Contrato exposto ao relay (não renomear sem alinhar o eixo de Mensageria). São aceitos os
 * dois dialetos do relay: {@code confirmarPublicacao/registrarFalha} e
 * {@code marcarPublicado/marcarFalha/marcarFalhaTerminal}. O caminho terminal do relay atual é
 * feito via JPA ({@code findById}+{@code save}); as variantes {@code marcar*} ficam disponíveis.
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Reserva até {@code limite} eventos {@code PENDING} vencidos, lockando as linhas
     * ({@code FOR UPDATE SKIP LOCKED}) para permitir relays concorrentes. Chamado dentro de uma
     * transação ({@code TransactionTemplate} no relay); o lock dura até o commit.
     */
    @Query(value = """
            SELECT * FROM lancamentos.outbox
             WHERE status = 'PENDING'
               AND next_attempt <= now()
             ORDER BY next_attempt, id
             LIMIT :limite
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> reservarLote(@Param("limite") int limite);

    @Query("SELECT o FROM OutboxEvent o WHERE o.eventId = :eventId")
    Optional<OutboxEvent> buscarPorEventId(@Param("eventId") UUID eventId);

    /** Confirma a publicação após o {@code ack} do broker (e ausência de {@code return}). */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE lancamentos.outbox
               SET status = 'PUBLISHED', published_at = now(), locked_until = NULL
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int confirmarPublicacao(@Param("id") Long id);

    /** Alias do relay (sem instante explícito → {@code now()}). */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE lancamentos.outbox
               SET status = 'PUBLISHED', published_at = now(), locked_until = NULL
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    void marcarPublicado(@Param("id") Long id);

    /** Alias do relay (com instante explícito). */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE lancamentos.outbox
               SET status = 'PUBLISHED', published_at = :publicadoEm, locked_until = NULL
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    void marcarPublicado(@Param("id") Long id, @Param("publicadoEm") Instant publicadoEm);

    /** Falha transitória: incrementa {@code attempts} e reagenda ({@code next_attempt}). */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE lancamentos.outbox
               SET attempts = attempts + 1,
                   next_attempt = now() + make_interval(secs => least(power(2, attempts + 1), 60)),
                   locked_until = NULL,
                   event_type = CASE WHEN :motivo IS NULL THEN event_type ELSE event_type END
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int registrarFalha(@Param("id") Long id, @Param("motivo") String motivo);

    /** Alias do relay (variante com instante explícito). */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE lancamentos.outbox
               SET attempts = attempts + 1, next_attempt = :proximaTentativa, locked_until = NULL
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    void marcarFalha(@Param("id") Long id, @Param("proximaTentativa") Instant proximaTentativa);

    /** Falha terminal (poison/rota não-casada): sai do caminho PENDING (ADR-009). */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE lancamentos.outbox
               SET status = 'FAILED', locked_until = NULL
             WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    void marcarFalhaTerminal(@Param("id") Long id);
}
