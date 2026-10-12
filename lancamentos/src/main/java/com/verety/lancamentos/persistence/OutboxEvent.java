package com.verety.lancamentos.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de {@code lancamentos.outbox}: Transactional Outbox (ADR-003).
 *
 * <p>Espelha o DDL canônico: PK simples {@code id}; {@code payload jsonb};
 * {@code status CHECK IN ('PENDING','PUBLISHED','FAILED')}; {@code attempts}/{@code next_attempt}/
 * {@code locked_until} para o relay {@code FOR UPDATE SKIP LOCKED}.
 */
@Entity
@Table(name = "outbox", schema = "lancamentos")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** JSON já serializado (o evento {@code LancamentoCriado}); coluna {@code jsonb}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt", nullable = false)
    private Instant nextAttempt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public Long getId() {
        return id;
    }

    /** Acesso no estilo record, consumido pelo relay ({@code OutboxRelay}). */
    public Long id() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }

    public UUID getEventId() {
        return eventId;
    }

    /** Acesso no estilo record, consumido pelo relay ({@code OutboxRelay}). */
    public UUID eventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    /** Acesso no estilo record, consumido pelo relay ({@code OutboxRelay}). */
    public String payload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttempt() {
        return nextAttempt;
    }

    public void setNextAttempt(Instant nextAttempt) {
        this.nextAttempt = nextAttempt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
