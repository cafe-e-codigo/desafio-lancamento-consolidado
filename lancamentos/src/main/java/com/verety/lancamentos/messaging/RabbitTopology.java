package com.verety.lancamentos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/**
 * Topologia AMQP do <b>write-side</b> (Serviço de Lançamentos): ADR-007.
 *
 * <p>O producer declara de forma <b>idempotente</b> os objetos que usa para publicar:
 * a exchange durável, a fila quorum e a DLQ/DLX (a DLX é referenciada nos argumentos da fila
 * e precisa existir). Os argumentos são <b>idênticos</b> aos declarados pelo consumidor
 * ({@code com.verety.consolidado.messaging.RabbitTopology}): divergência de args gera
 * {@code PRECONDITION_FAILED} no broker.
 *
 * <p>Garantias de entrega (at-least-once):
 * <ul>
 *   <li>{@code delivery_mode=2} (persistente) é setado por mensagem no {@code OutboxRelay};</li>
 *   <li>exchange e fila <b>duráveis</b>; {@code x-queue-type=quorum};</li>
 *   <li>{@code mandatory=true} + {@code basic.return} (rota não-casada não some em silêncio);</li>
 *   <li>{@code x-overflow=reject-publish} (nunca {@code drop-head} silencioso) + {@code x-max-length};</li>
 *   <li>{@code dead-letter-strategy=at-least-once} (default quorum é {@code at-most-once});</li>
 *   <li>{@code x-delivery-limit} <b>dimensionado</b> (failovers + redeliveries sem ack), nunca {@code 3} cru.</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} habilita o {@link OutboxRelay} ({@code @Scheduled}), no mesmo módulo.
 */
@Configuration
@EnableScheduling
public class RabbitTopology {

    /** Exchange principal (direta, durável). */
    public static final String EXCHANGE = "caixa.lancamentos.exchange";
    /** Dead-letter exchange (direta, durável). */
    public static final String DLX = "caixa.dlx";

    /** Fila de processamento (quorum, durável). */
    public static final String MAIN_QUEUE = "consolidado-processamento-queue";
    /** Dead-letter queue (quorum, durável). */
    public static final String DLQ = "consolidado-processamento-dlq";

    /** Routing key canônica do evento publicado. */
    public static final String ROUTING_LANCAMENTO_CRIADO = "lancamento.criado";
    /** Routing key da DLQ na {@link #DLX}. */
    public static final String ROUTING_DLQ = "consolidado.dlq";

    /**
     * {@code x-delivery-limit} <b>dimensionado</b>: ADR-007.
     * Conta redeliveries (inclui failover/restart do broker) e é o <b>failsafe</b> do hot-loop:
     * a retentativa de negócio é do app (retry-queue + {@code x-retry-count}), não daqui.
     * Valor generoso sobre as ~3 retentativas do app, para não matar mensagem VÁLIDA em failover.
     */
    public static final int DELIVERY_LIMIT = 20;

    /** Teto da fila (backstop de disco); {@code reject-publish} avisa o publisher via {@code basic.nack}. */
    public static final int MAX_LENGTH = 1_000_000;

    @Bean
    DirectExchange caixaLancamentosExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange caixaDlxExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue consolidadoProcessamentoQueue() {
        return new Queue(MAIN_QUEUE, true, false, false, mainQueueArgs());
    }

    @Bean
    Queue consolidadoProcessamentoDlq() {
        // Quorum (replicada) e durável; sem TTL, retenção é do ADR-010.
        return new Queue(DLQ, true, false, false, Map.of("x-queue-type", "quorum"));
    }

    @Bean
    Binding consolidadoProcessamentoBinding(Queue consolidadoProcessamentoQueue, DirectExchange caixaLancamentosExchange) {
        return BindingBuilder.bind(consolidadoProcessamentoQueue).to(caixaLancamentosExchange)
                .with(ROUTING_LANCAMENTO_CRIADO);
    }

    @Bean
    Binding consolidadoDlqBinding(Queue consolidadoProcessamentoDlq, DirectExchange caixaDlxExchange) {
        return BindingBuilder.bind(consolidadoProcessamentoDlq).to(caixaDlxExchange).with(ROUTING_DLQ);
    }

    /**
     * Args canônicos da fila principal (ADR-007). Mantidos idênticos no consumidor.
     */
    static Map<String, Object> mainQueueArgs() {
        return Map.of(
                "x-queue-type", "quorum",
                "x-delivery-limit", DELIVERY_LIMIT,
                "x-overflow", "reject-publish",
                "x-max-length", MAX_LENGTH,
                "x-dead-letter-exchange", DLX,
                "x-dead-letter-routing-key", ROUTING_DLQ,
                "x-dead-letter-strategy", "at-least-once"
        );
    }

    /**
     * Converte {@code LancamentoCriado} ↔ JSON usando o {@link ObjectMapper} do Boot
     * (inclui JavaTimeModule: {@code LocalDate}/{@code UUID}/{@code BigDecimal}).
     */
    @Bean
    MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
