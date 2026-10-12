package com.verety.consolidado.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA de {@code consolidado.processed_event}: LEDGER durável append-only (ADR-004).
 *
 * <p>Chave composta {@code (event_id, data)} (exigência do particionamento por {@code data});
 * o {@code ON CONFLICT (event_id, data) DO NOTHING} é o dedup idempotente. O marcador CARREGA
 * {@code valor_signed} (crédito &gt; 0 · débito &lt; 0), não existe {@code saldo_incremento}.
 */
@Entity
@IdClass(ProcessedEvent.Pk.class)
@Table(name = "processed_event", schema = "consolidado")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Id
    @Column(name = "data", nullable = false)
    private LocalDate data;

    @Column(name = "comerciante_id", nullable = false)
    private UUID comercianteId;

    @Column(name = "valor_signed", nullable = false, precision = 18, scale = 2)
    private BigDecimal valorSigned;

    @Column(name = "processado_em", nullable = false)
    private Instant processadoEm;

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public LocalDate getData() {
        return data;
    }

    public void setData(LocalDate data) {
        this.data = data;
    }

    public UUID getComercianteId() {
        return comercianteId;
    }

    public void setComercianteId(UUID comercianteId) {
        this.comercianteId = comercianteId;
    }

    public BigDecimal getValorSigned() {
        return valorSigned;
    }

    public void setValorSigned(BigDecimal valorSigned) {
        this.valorSigned = valorSigned;
    }

    public Instant getProcessadoEm() {
        return processadoEm;
    }

    public void setProcessadoEm(Instant processadoEm) {
        this.processadoEm = processadoEm;
    }

    /** Chave composta {@code (event_id, data)}: espelha a PK do DDL canônico. */
    public static class Pk implements Serializable {

        private UUID eventId;
        private LocalDate data;

        public Pk() {
        }

        public Pk(UUID eventId, LocalDate data) {
            this.eventId = eventId;
            this.data = data;
        }

        public UUID getEventId() {
            return eventId;
        }

        public LocalDate getData() {
            return data;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof Pk other
                    && Objects.equals(eventId, other.eventId)
                    && Objects.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, data);
        }
    }
}
