package com.verety.consolidado.service;

import com.verety.common.dto.LancamentoCriado;
import com.verety.consolidado.persistence.ProcessedEventRepository;
import com.verety.consolidado.persistence.SaldoDiarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumidor de domínio do read-side (CQRS). Chamado pelo
 * {@code com.verety.consolidado.messaging.LancamentoCriadoListener} a cada
 * evento; o ack no broker só acontece após o COMMIT desta transação (ADR-004).
 *
 * <p><b>Ledger idempotente:</b> {@code INSERT ... ON CONFLICT (event_id, data) DO NOTHING}.
 * Reentrega do mesmo evento ⇒ 0 linhas ⇒ no-op (sem novo efeito).
 *
 * <p><b>Rollup:</b> a projeção {@code saldo_diario} é escrita SÓ aqui, por
 * {@code (comerciante_id, data)}, nunca por evento direto. Ver
 * {@link SaldoDiarioRepository} para a sequência à prova de concorrência.
 *
 * <p><b>Invalidação de cache:</b> após o COMMIT do rollup, a chave de leitura
 * {@code (comerciante, data)} é evictada via {@link SaldoCacheEvictor}, sem isso o read-through
 * serviria um saldo velho até o TTL expirar. O evictor é <b>opcional</b> (contextos sem Redis
 * (ex.: fatia JPA) ficam sem cache e a invalidação é inerte).
 */
@Service
public class ConsolidacaoService {

    private static final String TIPO_CREDITO = "CREDITO";

    private final ProcessedEventRepository ledger;
    private final SaldoDiarioRepository saldos;
    private final SaldoCacheEvictor cache;

    public ConsolidacaoService(ProcessedEventRepository ledger, SaldoDiarioRepository saldos,
                               java.util.Optional<SaldoCacheEvictor> cache) {
        this.ledger = ledger;
        this.saldos = saldos;
        this.cache = cache.orElse(null);
    }

    /** Resultado do processamento: útil para métricas/observabilidade (ADR-009). */
    public record Resultado(boolean novo, UUID comercianteId, java.time.LocalDate data) {
    }

    /**
     * Anexa o evento ao ledger e, se for novo, atualiza a projeção. Idempotente: a reentrega
     * de um evento já visto não toca em {@code saldo_diario} nem invalida o cache.
     */
    @Transactional
    public Resultado processarEvento(LancamentoCriado evento) {
        UUID comercianteId = evento.comercianteId();
        BigDecimal valorSigned = assinar(evento);

        int inseridas = ledger.inserirSeAusente(
                evento.eventId(), comercianteId, evento.dataNegocio(), valorSigned);

        if (inseridas == 0) {
            return new Resultado(false, comercianteId, evento.dataNegocio());
        }
        executarRollup(comercianteId, evento.dataNegocio());
        agendarInvalidacao(comercianteId, evento.dataNegocio());
        return new Resultado(true, comercianteId, evento.dataNegocio());
    }

    /**
     * Rollup de uma chave {@code (comerciante_id, data)}: garante a linha, serializa pelo
     * {@code FOR UPDATE} e recomputa o valor absoluto do dia a partir do ledger. Uma única
     * UPDATE por chamada (1 UPDATE por grupo/janela).
     */
    private void executarRollup(UUID comercianteId, java.time.LocalDate data) {
        saldos.garantirLinha(comercianteId, data);
        saldos.travarValor(comercianteId, data);
        saldos.recomputarValor(comercianteId, data);
    }

    /**
     * Invalida o cache de leitura <b>após o commit</b> (senão o rollup não-commitado seria exposto
     * por um {@code GET} concorrente que reconstruísse o cache a partir do estado antigo).
     */
    private void agendarInvalidacao(UUID comercianteId, java.time.LocalDate data) {
        if (cache == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(comercianteId, data);
                }
            });
        } else {
            cache.evict(comercianteId, data);
        }
    }

    private static BigDecimal assinar(LancamentoCriado evento) {
        BigDecimal valor = evento.valor() == null ? BigDecimal.ZERO : evento.valor();
        if (valor.signum() < 0) {
            valor = valor.negate();
        }
        return TIPO_CREDITO.equalsIgnoreCase(evento.tipo()) ? valor : valor.negate();
    }
}
