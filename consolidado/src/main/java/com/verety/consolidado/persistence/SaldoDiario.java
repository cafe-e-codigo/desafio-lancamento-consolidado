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
 * Entidade JPA de {@code consolidado.saldo_diario}: PROJEÇÃO por {@code (comerciante_id, data)}.
 *
 * <p>Escrita EXCLUSIVAMENTE pelo rollup (1 UPDATE por grupo/janela), NUNCA por evento (ADR-006).
 * É a fonte de verdade da leitura; o Redis é só cache read-through.
 */
@Entity
@IdClass(SaldoDiario.Pk.class)
@Table(name = "saldo_diario", schema = "consolidado")
public class SaldoDiario {

    @Id
    @Column(name = "comerciante_id", nullable = false)
    private UUID comercianteId;

    @Id
    @Column(name = "data", nullable = false)
    private LocalDate data;

    @Column(name = "valor", nullable = false, precision = 18, scale = 2)
    private BigDecimal valor;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

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

    public BigDecimal getValor() {
        return valor;
    }

    public void setValor(BigDecimal valor) {
        this.valor = valor;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }

    public void setAtualizadoEm(Instant atualizadoEm) {
        this.atualizadoEm = atualizadoEm;
    }

    /** Chave composta {@code (comerciante_id, data)}. */
    public static class Pk implements Serializable {

        private UUID comercianteId;
        private LocalDate data;

        public Pk() {
        }

        public Pk(UUID comercianteId, LocalDate data) {
            this.comercianteId = comercianteId;
            this.data = data;
        }

        public UUID getComercianteId() {
            return comercianteId;
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
                    && Objects.equals(comercianteId, other.comercianteId)
                    && Objects.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(comercianteId, data);
        }
    }
}
