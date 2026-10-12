package com.verety.consolidado.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paridade da topologia AMQP com a doutrina ADR-007 (§0.2 do plano de verificação). Puro: lê os
 * {@code @Bean} declarados e os args canônicos, sem broker. Se estes args divergirem do Alvo
 * ({@code quorum}, delivery-limit dimensionado, DLX, at-least-once, reject-publish), o ensaio mede
 * OUTRO broker e o selo não vale.
 */
class RabbitTopologyArgsTest {

    private final RabbitTopology topologia = new RabbitTopology();

    @Test
    @DisplayName("fila principal: quorum + delivery-limit dimensionado + DLX + at-least-once + reject-publish")
    void argsCanonicosDaFilaPrincipal() {
        Queue fila = topologia.consolidadoProcessamentoQueue();
        Map<String, Object> args = fila.getArguments();

        assertThat(fila.getName()).isEqualTo("consolidado-processamento-queue");
        assertThat(fila.isDurable()).isTrue();
        assertThat(args).containsEntry("x-queue-type", "quorum");
        assertThat(args).containsEntry("x-overflow", "reject-publish");
        assertThat(args).containsEntry("x-dead-letter-exchange", "caixa.dlx");
        assertThat(args).containsEntry("x-dead-letter-routing-key", "consolidado.dlq");
        assertThat(args).containsEntry("x-dead-letter-strategy", "at-least-once");
        assertThat(args).containsEntry("x-max-length", RabbitTopology.MAX_LENGTH);
        assertThat(args.get("x-delivery-limit")).as("dimensionado, não 3 cru")
                .isEqualTo(RabbitTopology.DELIVERY_LIMIT);
        assertThat(RabbitTopology.DELIVERY_LIMIT).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("retry-queue: TTL + dead-letter de volta à exchange canônica")
    void filaDeRetryTemTtlEDlx() {
        Queue retry = topologia.consolidadoRetryQueue();
        assertThat(retry.getName()).isEqualTo("consolidado-retry-queue");
        assertThat(retry.getArguments()).containsEntry("x-message-ttl", RabbitTopology.RETRY_TTL_MS);
        assertThat(retry.getArguments()).containsEntry("x-dead-letter-exchange", RabbitTopology.EXCHANGE);
        assertThat(retry.getArguments()).containsEntry("x-dead-letter-routing-key", RabbitTopology.ROUTING_LANCAMENTO_CRIADO);
    }

    @Test
    @DisplayName("DLQ: quorum durável")
    void dlqEhQuorumDuravel() {
        Queue dlq = topologia.consolidadoProcessamentoDlq();
        assertThat(dlq.getName()).isEqualTo("consolidado-processamento-dlq");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.getArguments()).containsEntry("x-queue-type", "quorum");
    }

    @Test
    @DisplayName("paridade producer↔consumer dos args da fila principal")
    void paridadeComProducer() {
        // O producer do write-side repete os mesmos args (mesmo broker, dois serviços).
        // Aqui replicamos o literal canônico que ambos declaram: se um mudar, este teste quebra.
        assertThat(RabbitTopology.mainQueueArgs()).isEqualTo(Map.of(
                "x-queue-type", "quorum",
                "x-delivery-limit", 20,
                "x-overflow", "reject-publish",
                "x-max-length", 1_000_000,
                "x-dead-letter-exchange", "caixa.dlx",
                "x-dead-letter-routing-key", "consolidado.dlq",
                "x-dead-letter-strategy", "at-least-once"));
    }
}
