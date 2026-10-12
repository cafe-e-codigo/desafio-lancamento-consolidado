package com.verety.consolidado.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Topologia AMQP do <b>read-side</b> (Serviço de Consolidado): ADR-007.
 *
 * <p>O consumidor declara os mesmos objetos, com os <b>mesmos argumentos</b>, do producer
 * do write-side; args divergentes causam {@code PRECONDITION_FAILED}. O módulo
 * {@code consolidado} <b>não</b> depende do módulo {@code lancamentos}: as constantes são
 * repetidas aqui de propósito (mesmo broker, dois serviços; a verdade é o ADR-007, não uma
 * classe compartilhada).
 *
 * <p>Garantia: <b>at-least-once</b>. O dedup/efeito é do consumidor
 * ({@code ConsolidacaoService.processarEvento} + {@code ON CONFLICT (event_id, data)}),
 * nunca do broker.
 */
@Configuration
public class RabbitTopology {

    /** Exchange principal (direta, durável). */
    public static final String EXCHANGE = "caixa.lancamentos.exchange";
    /** Dead-letter exchange (direta, durável). */
    public static final String DLX = "caixa.dlx";

    /** Fila de processamento (quorum, durável). */
    public static final String MAIN_QUEUE = "consolidado-processamento-queue";
    /** Dead-letter queue (quorum, durável). */
    public static final String DLQ = "consolidado-processamento-dlq";
    /** Fila de retry (TTL+DLX); classic, atraso temporário por definição. */
    public static final String RETRY_QUEUE = "consolidado-retry-queue";

    /** Routing key canônica do evento publicado. */
    public static final String ROUTING_LANCAMENTO_CRIADO = "lancamento.criado";
    /** Routing key da DLQ na {@link #DLX}. */
    public static final String ROUTING_DLQ = "consolidado.dlq";
    /** Routing key que entrega na {@link #RETRY_QUEUE}. */
    public static final String ROUTING_RETRY = "lancamento.criado.retry";

    /** {@code x-delivery-limit} dimensionado: idêntico ao producer (ADR-007). */
    public static final int DELIVERY_LIMIT = 20;
    /** Teto da fila principal: idêntico ao producer (ADR-007). */
    public static final int MAX_LENGTH = 1_000_000;
    /** TTL do retry (ms). Backoff fixo; o teto de tentativas é do header {@code x-retry-count}. */
    public static final int RETRY_TTL_MS = 30_000;

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
        return new Queue(DLQ, true, false, false, Map.of("x-queue-type", "quorum"));
    }

    /**
     * Fila de retry: TTL + dead-letter de volta à {@link #EXCHANGE} com a routing key
     * {@link #ROUTING_LANCAMENTO_CRIADO}. O handler transiente <b>reenvia</b> para cá com
     * {@code x-retry-count++}; após o TTL a mensagem reentra na fila principal.
     */
    @Bean
    Queue consolidadoRetryQueue() {
        return new Queue(RETRY_QUEUE, true, false, false, Map.of(
                "x-message-ttl", RETRY_TTL_MS,
                "x-dead-letter-exchange", EXCHANGE,
                "x-dead-letter-routing-key", ROUTING_LANCAMENTO_CRIADO
        ));
    }

    @Bean
    Binding consolidadoProcessamentoBinding(Queue consolidadoProcessamentoQueue, DirectExchange caixaLancamentosExchange) {
        return BindingBuilder.bind(consolidadoProcessamentoQueue).to(caixaLancamentosExchange)
                .with(ROUTING_LANCAMENTO_CRIADO);
    }

    /** Retry-queue recebe pela routing key de retry; a fila principal, pela canônica. */
    @Bean
    Binding consolidadoRetryBinding(Queue consolidadoRetryQueue, DirectExchange caixaLancamentosExchange) {
        return BindingBuilder.bind(consolidadoRetryQueue).to(caixaLancamentosExchange).with(ROUTING_RETRY);
    }

    @Bean
    Binding consolidadoDlqBinding(Queue consolidadoProcessamentoDlq, DirectExchange caixaDlxExchange) {
        return BindingBuilder.bind(consolidadoProcessamentoDlq).to(caixaDlxExchange).with(ROUTING_DLQ);
    }

    /**
     * Listener com <b>ack manual</b> (ack só após COMMIT no ledger, ADR-004/007) e
     * <b>prefetch=10</b> (janela in-flight limitada, ADR-007). Usa o mesmo
     * {@link MessageConverter} do publisher, garantindo simetria write/read.
     */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter rabbitMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(10);
        factory.setMessageConverter(rabbitMessageConverter);
        return factory;
    }

    /**
     * Converte {@code LancamentoCriado} ↔ JSON usando o {@link ObjectMapper} do Boot
     * (inclui JavaTimeModule: {@code LocalDate}/{@code UUID}/{@code BigDecimal}): <b>espelho
     * do write-side</b>; mesmo converter nos dois lados garante que o evento publicado
     * desserializa no consumidor. O {@code __TypeId__} aponta para o FQN canônico do record.
     */
    @Bean
    MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * Template explícito do read-side (reenvio do transiente à retry-queue) com o mesmo
     * converter. Configurado pelo {@link RabbitTemplateConfigurer} do Boot para herdar
     * {@code mandatory}/{@code publisher-confirm-type} do {@code application.yml}.
     */
    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, RabbitTemplateConfigurer configurer) {
        RabbitTemplate template = new RabbitTemplate();
        configurer.configure(template, connectionFactory);
        return template;
    }

    /** Args canônicos da fila principal: idênticos aos do producer (ADR-007). */
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
}
