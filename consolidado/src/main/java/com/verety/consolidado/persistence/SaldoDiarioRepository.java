package com.verety.consolidado.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Projeção {@code consolidado.saldo_diario}. O rollup escreve via três passos que evitam
 * <i>lost update</i> sob consumidores concorrentes:
 * <ol>
 *   <li>{@link #garantirLinha}: cria a linha zerada se ainda não existe (idempotente);</li>
 *   <li>{@link #travarValor}: {@code SELECT ... FOR UPDATE} serializa por {@code (comerciante,data)}
 *       (a linha fica travada até o commit);</li>
 *   <li>{@link #recomputarValor}: recomputa o valor ABSOLUTO a partir do ledger numa nova
 *       sentença (vê o que os concorrentes já commitaram), imune a dupla contagem e a reentrega.</li>
 * </ol>
 * Diferente do incremento por watermark, o valor absoluto auto-corrige: o invariante
 * {@code saldo.valor == SUM(processed_event.valor_signed)} vale após QUALQUER evento.
 */
public interface SaldoDiarioRepository extends JpaRepository<SaldoDiario, SaldoDiario.Pk> {

    @Query(value = """
            SELECT * FROM consolidado.saldo_diario
             WHERE comerciante_id = :comercianteId AND data = :data
            """, nativeQuery = true)
    Optional<SaldoDiario> buscar(@Param("comercianteId") UUID comercianteId,
                                 @Param("data") LocalDate data);

    @Modifying
    @Query(value = """
            INSERT INTO consolidado.saldo_diario (comerciante_id, data, valor, atualizado_em)
            VALUES (:comercianteId, :data, 0, now())
            ON CONFLICT (comerciante_id, data) DO NOTHING
            """, nativeQuery = true)
    int garantirLinha(@Param("comercianteId") UUID comercianteId,
                      @Param("data") LocalDate data);

    @Query(value = """
            SELECT valor FROM consolidado.saldo_diario
             WHERE comerciante_id = :comercianteId AND data = :data
             FOR UPDATE
            """, nativeQuery = true)
    BigDecimal travarValor(@Param("comercianteId") UUID comercianteId,
                           @Param("data") LocalDate data);

    @Modifying
    @Query(value = """
            UPDATE consolidado.saldo_diario
               SET valor = (SELECT COALESCE(SUM(valor_signed), 0)
                              FROM consolidado.processed_event
                             WHERE comerciante_id = :comercianteId AND data = :data),
                   atualizado_em = now()
             WHERE comerciante_id = :comercianteId AND data = :data
            """, nativeQuery = true)
    int recomputarValor(@Param("comercianteId") UUID comercianteId,
                        @Param("data") LocalDate data);
}
