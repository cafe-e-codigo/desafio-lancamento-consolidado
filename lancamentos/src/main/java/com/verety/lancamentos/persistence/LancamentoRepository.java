package com.verety.lancamentos.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório do write-side. A dedup de ORIGEM é por {@code transaction_id} = {@code event_id}
 * determinístico (ADR-005).
 */
public interface LancamentoRepository extends JpaRepository<Lancamento, Long> {

    Optional<Lancamento> findFirstByTransactionId(UUID transactionId);

    List<Lancamento> findByComercianteIdAndData(UUID comercianteId, LocalDate data);
}
