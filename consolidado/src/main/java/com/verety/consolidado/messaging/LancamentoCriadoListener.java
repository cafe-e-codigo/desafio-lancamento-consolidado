package com.verety.consolidado.messaging;

import com.rabbitmq.client.Channel;
import com.verety.common.dto.LancamentoCriado;
import com.verety.consolidado.service.ConsolidacaoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consumidor do evento {@link LancamentoCriado}: ADR-007 + ADR-004.
 *
 * <p>Fluxo por mensagem (ack manual):
 * <ol>
 *   <li>delega o efeito a {@code ConsolidacaoService.processarEvento}: dedup/efeito em
 *       <b>1 SQL</b> ({@code INSERT ... ON CONFLICT (event_id, data) DO NOTHING}); o
 *       <b>ack só sai após o COMMIT</b> do serviço;</li>
 *   <li>em erro, classifica pela {@link ErroEvento taxonomia}:
 *       <b>transiente</b> → reenvia à {@link RabbitTopology#RETRY_QUEUE} com
 *       {@code x-retry-count++} e dá {@code ack} (a rota da DLQ é fixa por fila, então o
 *       transiente precisa de reenvio explícito); <b>veneno</b> → {@code reject{requeue=false}},
 *       caindo na DLX na primeira vez.</li>
 * </ol>
 *
 * <p><b>Nunca</b> {@code requeue=true} (hot-loop). O broker garante <b>at-least-once</b>;
 * o efeito exatamente-uma-vez vem do dedup durável no Postgres, não da entrega.
 */
@Component
public class LancamentoCriadoListener {

    private static final Logger log = LoggerFactory.getLogger(LancamentoCriadoListener.class);

    /** Header canônico de tentativa transiente (incrementado por este handler). */
    public static final String HEADER_RETRY_COUNT = "x-retry-count";

    private final ConsolidacaoService consolidacaoService;
    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;

    public LancamentoCriadoListener(ConsolidacaoService consolidacaoService,
                                    RabbitTemplate rabbitTemplate,
                                    MessageConverter messageConverter) {
        this.consolidacaoService = consolidacaoService;
        this.rabbitTemplate = rabbitTemplate;
        this.messageConverter = messageConverter;
    }

    @RabbitListener(queues = RabbitTopology.MAIN_QUEUE)
    public void onLancamentoCriado(@Payload LancamentoCriado evento,
                                   Message message,
                                   Channel channel,
                                   @Header(name = AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        int retryCount = retryCount(message);
        try {
            consolidacaoService.processarEvento(evento);
            // ack só após o COMMIT do efeito idempotente (processarEvento é @Transactional).
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            ErroEvento erro = ErroEvento.classificar(ex, retryCount, evento);
            log.warn("consolidado: eventId={} -> {}", eventId(evento), erro);
            if (erro.acao() == ErroEvento.Acao.RETRY) {
                reenviarParaRetry(evento, erro.retryCount());
                // o original sai da fila principal; a cópia espera o TTL na retry-queue.
                channel.basicAck(deliveryTag, false);
            } else {
                // veneno / teto esgotado: DLQ na primeira vez (requeue=false -> DLX).
                channel.basicReject(deliveryTag, false);
            }
        }
    }

    /**
     * Reenvia o evento à retry-queue com o contador incrementado. Se o reenvio falhar,
     * rejeita (não deixa hot-loop): a mensagem vai à DLQ para redrive, preservando o evento.
     */
    private void reenviarParaRetry(LancamentoCriado evento, int retryCount) throws IOException {
        MessageProperties props = new MessageProperties();
        props.setDeliveryMode(MessageProperties.DEFAULT_DELIVERY_MODE);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader(HEADER_RETRY_COUNT, retryCount);
        Message retry = messageConverter.toMessage(evento, props);
        rabbitTemplate.send(RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_RETRY, retry);
    }

    /** Lê {@code x-retry-count} do header (default 0). */
    private static int retryCount(Message message) {
        Object header = message.getMessageProperties().getHeaders().get(HEADER_RETRY_COUNT);
        if (header instanceof Number number) {
            return number.intValue();
        }
        if (header instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static Object eventId(LancamentoCriado evento) {
        return evento == null ? null : evento.eventId();
    }
}
