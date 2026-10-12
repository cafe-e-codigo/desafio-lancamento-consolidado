package com.verety.lancamentos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verety.common.dto.LancamentoCriado;
import com.verety.lancamentos.persistence.OutboxEvent;
import com.verety.lancamentos.persistence.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Relay do <b>Transactional Outbox</b> (ADR-003): tira o broker do caminho crítico do
 * {@code POST} (RNF#1) e publica de forma assíncrona.
 *
 * <p>Fluxo por varredura:
 * <ol>
 *   <li>{@link OutboxRepository#reservarLote(int)} reserva um lote de linhas {@code PENDING}
 *       com {@code FOR UPDATE SKIP LOCKED} <b>dentro de uma transação</b> (o lock só existe
 *       dentro dela); a transação fecha sem segurar lock durante I/O de rede.</li>
 *   <li>Cada linha é publicada com {@code delivery_mode=2} e {@code mandatory=true}.</li>
 *   <li>Confirmação <b>por {@code delivery_tag}</b> (correlation data): só marca
 *       {@code PUBLISHED} quando o broker <b>ack</b> e <b>não</b> houve {@code basic.return}.
 *       {@code nack} de {@code reject-publish} (backpressure) é tratado como falha retentável.
 *       Confirm que não chega no prazo <b>não</b> marca nada: a linha volta a ser varrida
 *       (at-least-once; duplicata é esperada e absorvida pelo consumidor idempotente, ADR-004).</li>
 * </ol>
 *
 * <p>Terminal: {@code basic.return} (rota não-casada, com {@code mandatory}) é <b>misconfig</b>
 * ou evento inválido: não adianta retentar rota. Após {@link #MAX_ATTEMPTS} falhas retentáveis,
 * a linha vira {@code FAILED} e sai do caminho PENDING para triagem/alarme (ADR-003/009).
 * O {@link OutboxRepository} não expõe método de falha terminal; o estado é aplicado aqui
 * via JPA (merge da entidade): <b>sem editar o repositório</b>.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Tamanho do lote reservado por varredura. */
    private static final int BATCH = 50;

    /** Prazo do confirm antes de considerar a linha não-confirmada (deixa PENDING). */
    private static final long CONFIRM_TIMEOUT_MS = 5_000L;

    /** Teto de tentativas antes de {@code FAILED} terminal (ADR-003). */
    private static final int MAX_ATTEMPTS = 10;

    private static final String STATUS_FAILED = "FAILED";

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelay(OutboxRepository outboxRepository,
                       RabbitTemplate rabbitTemplate,
                       ObjectMapper objectMapper,
                       PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Varredura periódica (default 200 ms, ADR-003: {@code poll_interval ≤ 200 ms}). */
    @Scheduled(fixedDelayString = "${verety.outbox.relay-interval-ms:200}")
    public void relay() {
        List<OutboxEvent> lote = transactionTemplate.execute(
                status -> outboxRepository.reservarLote(BATCH));
        if (lote == null || lote.isEmpty()) {
            return;
        }

        List<CorrelationData> pendentes = new ArrayList<>(lote.size());
        List<OutboxEvent> pendentesLinhas = new ArrayList<>(lote.size());

        for (OutboxEvent linha : lote) {
            try {
                LancamentoCriado evento = objectMapper.readValue(linha.getPayload(), LancamentoCriado.class);
                CorrelationData correlation = new CorrelationData(linha.getEventId().toString());
                rabbitTemplate.convertAndSend(
                        RabbitTopology.EXCHANGE,
                        RabbitTopology.ROUTING_LANCAMENTO_CRIADO,
                        evento,
                        message -> {
                            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            message.getMessageProperties().setMessageId(linha.getEventId().toString());
                            // event_id estável fim-a-fim (ADR-005): relay, consumidor e redrive.
                            message.getMessageProperties().setHeader("event_id", linha.getEventId().toString());
                            return message;
                        },
                        correlation);
                pendentes.add(correlation);
                pendentesLinhas.add(linha);
            } catch (Exception e) {
                log.warn("outbox: falha ao publicar id={} eventId={}", linha.getId(), linha.getEventId(), e);
                registrarFalha(linha, "PUBLISH_EXC: " + e.getMessage());
            }
        }

        for (int i = 0; i < pendentes.size(); i++) {
            CorrelationData correlation = pendentes.get(i);
            OutboxEvent linha = pendentesLinhas.get(i);
            try {
                CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                boolean returned = correlation.getReturned() != null;
                if (!returned && confirm != null && confirm.isAck()) {
                    emTransacao(() -> outboxRepository.confirmarPublicacao(linha.getId()));
                } else if (returned) {
                    // Rota não-casada (mandatory + basic.return): misconfig/veneno, não retenta rota.
                    String motivo = "UNROUTABLE: " + correlation.getReturned().getReplyText();
                    log.error("outbox: {} id={} eventId={}", motivo, linha.getId(), linha.getEventId());
                    marcarTerminal(linha, motivo);
                } else {
                    // basic.nack (ex.: reject-publish por fila cheia) → backpressure, retenta.
                    String motivo = "NACK: " + (confirm == null ? "null" : confirm.getReason());
                    log.warn("outbox: {} id={} eventId={}", motivo, linha.getId(), linha.getEventId());
                    registrarFalha(linha, motivo);
                }
            } catch (Exception e) {
                // Sem confirm no prazo: não marca nada; a reserva some e a varredura re-tenta.
                log.warn("outbox: confirm não chegou no prazo id={} eventId={}", linha.getId(), linha.getEventId(), e);
            }
        }
    }

    /** Falha retentável: incrementa {@code attempts} e reagenda; esgotado ⇒ {@code FAILED} terminal. */
    private void registrarFalha(OutboxEvent linha, String motivo) {
        if (linha.getAttempts() + 1 >= MAX_ATTEMPTS) {
            marcarTerminal(linha, "ESGOTADO(" + (linha.getAttempts() + 1) + "): " + motivo);
            return;
        }
        emTransacao(() -> outboxRepository.registrarFalha(linha.getId(), motivo));
    }

    /**
     * Aplica {@code FAILED} terminal via JPA (o repositório só expõe retentável).
     * Só transiciona a partir de {@code PENDING}.
     */
    private void marcarTerminal(OutboxEvent linha, String motivo) {
        emTransacao(() -> outboxRepository.findById(linha.getId()).ifPresent(persistido -> {
            if ("PENDING".equals(persistido.getStatus())) {
                persistido.setStatus(STATUS_FAILED);
                persistido.setLockedUntil(null);
                outboxRepository.save(persistido);
            }
        }));
    }

    private void emTransacao(Runnable acao) {
        transactionTemplate.executeWithoutResult(status -> acao.run());
    }
}
