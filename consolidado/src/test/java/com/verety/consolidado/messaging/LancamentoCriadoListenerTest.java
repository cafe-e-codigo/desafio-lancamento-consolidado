package com.verety.consolidado.messaging;

import com.rabbitmq.client.Channel;
import com.verety.common.dto.LancamentoCriado;
import com.verety.consolidado.service.ConsolidacaoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

/**
 * Na fronteira do consumidor (ADR-004/007): o {@code ack} só sai DEPOIS do efeito, e a
 * taxonomia transiente × veneno roteia diferente. Sem broker: o oráculo é o {@link Channel}.
 *
 * <ul>
 *   <li>sucesso ⇒ {@code basicAck} (pós-efeito), nunca {@code reject};</li>
 *   <li>transiente ⇒ reenvia à retry-queue com {@code x-retry-count++} e dá ack no original;</li>
 *   <li>veneno ⇒ {@code basicReject(requeue=false)} na 1ª vez (DLQ), sem retry.</li>
 * </ul>
 */
class LancamentoCriadoListenerTest {

    private static final long DELIVERY_TAG = 42L;

    private final ConsolidacaoService service = mock(ConsolidacaoService.class);
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final MessageConverter converter = mock(MessageConverter.class);
    private final Channel channel = mock(Channel.class);

    private final LancamentoCriadoListener listener =
            new LancamentoCriadoListener(service, template, converter);

    private static final LancamentoCriado EVENTO = new LancamentoCriado(
            UUID.randomUUID(), 1L, UUID.randomUUID(), "CREDITO",
            new BigDecimal("10.00"), "BRL", LocalDate.of(2026, 10, 8), UUID.randomUUID());

    @Test
    @DisplayName("sucesso ⇒ basicAck DEPOIS do efeito (ack pós-COMMIT)")
    void ackDepoisDoEfeito() throws Exception {
        when(service.processarEvento(EVENTO)).thenReturn(
                new ConsolidacaoService.Resultado(true, EVENTO.comercianteId(), EVENTO.dataNegocio()));

        listener.onLancamentoCriado(EVENTO, mensagemVazia(), channel, DELIVERY_TAG);

        InOrder ordem = inOrder(service, channel);
        ordem.verify(service).processarEvento(EVENTO);
        ordem.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicReject(anyInt(), any(Boolean.class));
    }

    @Test
    @DisplayName("transiente ⇒ reenvia à retry-queue e ack no original (sem hot-loop)")
    void transienteReenviaParaRetry() throws Exception {
        doThrow(new RuntimeException("db fora")).when(service).processarEvento(EVENTO);
        when(converter.toMessage(any(), any(MessageProperties.class)))
                .thenReturn(new Message("{}".getBytes(), new MessageProperties()));

        listener.onLancamentoCriado(EVENTO, mensagemVazia(), channel, DELIVERY_TAG);

        verify(template, times(1)).send(eq(RabbitTopology.EXCHANGE), eq(RabbitTopology.ROUTING_RETRY), any(Message.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicReject(anyInt(), any(Boolean.class));
    }

    @Test
    @DisplayName("veneno ⇒ basicReject(requeue=false) na 1ª vez (DLQ), sem retry")
    void venenoVaiParaDlqSemRetry() throws Exception {
        doThrow(new IllegalArgumentException("payload corrompido")).when(service).processarEvento(EVENTO);

        listener.onLancamentoCriado(EVENTO, mensagemVazia(), channel, DELIVERY_TAG);

        verify(channel).basicReject(DELIVERY_TAG, false);
        verify(template, never()).send(any(), any(), any(Message.class));
        verify(channel, never()).basicAck(anyInt(), eq(false));
    }

    private static Message mensagemVazia() {
        return new Message("{}".getBytes(), new MessageProperties());
    }
}
