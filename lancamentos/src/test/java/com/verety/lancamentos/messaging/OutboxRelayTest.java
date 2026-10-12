package com.verety.lancamentos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verety.common.dto.LancamentoCriado;
import com.verety.lancamentos.persistence.OutboxEvent;
import com.verety.lancamentos.persistence.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Propriedades de publicação do relay (ADR-003/ADR-007): puro, com {@link RabbitTemplate} dublado.
 *
 * <p>Prova que cada mensagem sai com {@code delivery_mode=2} (persistente) e {@code message_id}
 * = {@code event_id} (idempotência fim-a-fim no broker). Um broker real é caro/flaky; aqui a
 * fronteira testada é o <b>envelope AMQP construído pelo relay</b>, capturado no 4º argumento
 * ({@link MessagePostProcessor}), e a confirmação é resolvida para o fluxo seguir.
 */
class OutboxRelayTest {

    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    @DisplayName("relay publica com delivery_mode=2 (persistente) e message_id=event_id")
    void publicaPersistenteComMessageId() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LancamentoCriado evento = new LancamentoCriado(EVENT_ID, 1L, UUID.randomUUID(), "CREDITO",
                new BigDecimal("10.00"), "BRL", LocalDate.of(2026, 10, 8), UUID.randomUUID());

        OutboxEvent linha = new OutboxEvent();
        linha.setId(7L);
        linha.setEventId(EVENT_ID);
        linha.setPayload(mapper.writeValueAsString(evento));
        linha.setStatus("PENDING");
        linha.setAttempts(0);
        linha.setNextAttempt(Instant.now());
        linha.setCreatedAt(Instant.now());

        OutboxRepository repo = mock(OutboxRepository.class);
        when(repo.reservarLote(any(Integer.class))).thenReturn(List.of(linha));
        when(repo.confirmarPublicacao(any())).thenReturn(1);

        RabbitTemplate template = mock(RabbitTemplate.class);
        AtomicReference<Message> envelope = new AtomicReference<>();
        doAnswer(inv -> {
            MessagePostProcessor mpp = inv.getArgument(3);
            CorrelationData correlation = inv.getArgument(4);
            Message message = mpp.postProcessMessage(new Message("{}".getBytes(), new MessageProperties()));
            envelope.set(message);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null)); // ack imediato
            return null;
        }).when(template).convertAndSend(
                eq(RabbitTopology.EXCHANGE), eq(RabbitTopology.ROUTING_LANCAMENTO_CRIADO),
                any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        OutboxRelay relay = new OutboxRelay(repo, template, mapper, new TxManager());
        relay.relay();

        assertThat(envelope.get()).isNotNull();
        assertThat(envelope.get().getMessageProperties().getDeliveryMode())
                .as("delivery_mode=2: mensagem sobrevive a restart do broker")
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(envelope.get().getMessageProperties().getMessageId())
                .as("message_id = event_id (dedup fim-a-fim)")
                .isEqualTo(EVENT_ID.toString());
        assertThat(envelope.get().getMessageProperties().getHeaders().get("event_id"))
                .isEqualTo(EVENT_ID.toString());
    }

    /** TransactionTemplate mínimo: nenhuma transação real, só o contrato do relay. */
    private static final class TxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
