package com.verety.consolidado.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ledger durável ({@code consolidado.processed_event}). O INSERT é o único ponto de dedup:
 * {@code ON CONFLICT (event_id, data) DO NOTHING} ⇒ reentrega vira no-op (ADR-004).
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Pk> {

    /**
     * Anexa o evento ao ledger. Retorna {@code 1} se inseriu (primeira vez) e {@code 0} se já
     * existia (reentrega), a decisão de "processar o rollup" sai do contador.
     */
    @Modifying
    @Query(value = """
            INSERT INTO consolidado.processed_event
                   (event_id, comerciante_id, data, valor_signed, processado_em)
            VALUES (:eventId, :comercianteId, :data, :valorSigned, now())
            ON CONFLICT (event_id, data) DO NOTHING
            """, nativeQuery = true)
    int inserirSeAusente(@Param("eventId") UUID eventId,
                         @Param("comercianteId") UUID comercianteId,
                         @Param("data") LocalDate data,
                         @Param("valorSigned") BigDecimal valorSigned);
}
