package com.verety.lancamentos.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Entidade JPA de {@code lancamentos.lancamento} (write-side).
 *
 * <p>Espelha o DDL canônico ({@code db/migration/V1__lancamentos.sql}):
 * {@code valor numeric(18,2)}, {@code data} = data de NEGÓCIO (não {@code now()}),
 * {@code transaction_id} = {@code event_id} determinístico (ADR-005).
 *
 * <p>Nota de mapeamento: o PK físico é composto {@code (comerciante_id, data, id)} (exigência de
 * particionamento por {@code data}). Esta entidade usa {@code id} como {@code @Id} para INSERT/leitura
 * por id; todas as leituras de negócio passam por {@code transaction_id} (query derivada).
 */
@Entity
@Table(name = "lancamento", schema = "lancamentos")
public class Lancamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "comerciante_id", nullable = false)
    private UUID comercianteId;

    @Column(name = "data", nullable = false)
    private LocalDate data;

    @Column(name = "tipo", nullable = false, length = 10)
    private String tipo;

    @Column(name = "valor", nullable = false, precision = 18, scale = 2)
    private BigDecimal valor;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public UUID getComercianteId() {
        return comercianteId;
    }

    public void setComercianteId(UUID comercianteId) {
        this.comercianteId = comercianteId;
    }

    public LocalDate getData() {
        return data;
    }

    public void setData(LocalDate data) {
        this.data = data;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public void setValor(BigDecimal valor) {
        this.valor = valor;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public void setCriadoEm(Instant criadoEm) {
        this.criadoEm = criadoEm;
    }
}
