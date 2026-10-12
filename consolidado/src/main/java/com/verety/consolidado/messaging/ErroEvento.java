package com.verety.consolidado.messaging;

import com.verety.common.dto.LancamentoCriado;

/**
 * Classificação de erro do consumidor: <b>taxonomia</b> do ADR-007.
 *
 * <p>O núcleo da correção: <b>transiente ≠ veneno</b>.
 * <ul>
 *   <li><b>Transiente</b> (infra: DB fora, timeout, lock, broker blip) → {@code RETRY}
 *       ({@code nack{requeue=false}} para a {@code consolidado-retry-queue}, sem queimar a
 *       entrega), a retentativa é medida por {@code x-retry-count} no header.</li>
 *   <li><b>Veneno</b> (schema/dado inválido, valor fora do contrato) → {@code DLQ}
 *       ({@code reject{requeue=false}} na <b>primeira</b> vez, sem consumir retry).</li>
 * </ul>
 *
 * <p>Sem essa distinção, {@code x-delivery-limit} e a retry-queue não sabem diferenciar
 * poison de transiente e a fila queima entrega válida.
 */
public final class ErroEvento {

    /** Ação a tomar sobre a mensagem, decidida no handler. */
    public enum Acao {
        /** Vai à {@code consolidado-retry-queue} (TTL+DLX) com {@code x-retry-count++}. */
        RETRY,
        /** Vai direto à DLQ (poison). */
        DLQ
    }

    /** Teto canônico de retentativas transientes antes de mandar à DLQ. */
    public static final int MAX_RETRIES = 3;

    private final Acao acao;
    private final int retryCount;
    private final String motivo;

    private ErroEvento(Acao acao, int retryCount, String motivo) {
        this.acao = acao;
        this.retryCount = retryCount;
        this.motivo = motivo;
    }

    public Acao acao() {
        return acao;
    }

    /** Tentativas já feitas ({@code x-retry-count} da mensagem atual). */
    public int retryCount() {
        return retryCount;
    }

    public String motivo() {
        return motivo;
    }

    /**
     * Classifica a falha do consumidor.
     *
     * @param ex         exceção capturada do processamento
     * @param retryCount {@code x-retry-count} da mensagem recebida
     * @param evento     evento desserializado (para classificação; pode ser {@code null})
     * @return ação + contador a aplicar
     */
    public static ErroEvento classificar(Exception ex, int retryCount, LancamentoCriado evento) {
        String motivo = ex == null ? "desconhecido" : ex.getClass().getSimpleName() + ": " + ex.getMessage();

        // VENENO: payload inválido / fora do contrato: nunca retenta, vai à DLQ na 1ª vez.
        if (ex instanceof IllegalArgumentException
                || ex instanceof com.fasterxml.jackson.core.JsonProcessingException
                || !eventoValido(evento)) {
            return new ErroEvento(Acao.DLQ, retryCount, "VENENO: " + motivo);
        }

        // TRANSIENTE: infraestrutura. Retenta até o teto; acima do teto, DLQ.
        if (retryCount >= MAX_RETRIES) {
            return new ErroEvento(Acao.DLQ, retryCount, "TRANSIENTE_ESGOTADO(" + retryCount + "): " + motivo);
        }
        return new ErroEvento(Acao.RETRY, retryCount + 1, "TRANSIENTE(" + (retryCount + 1) + "): " + motivo);
    }

    /** Contrato mínimo do evento: campos obrigatórios presentes e valor positivo. */
    private static boolean eventoValido(LancamentoCriado evento) {
        return evento != null
                && evento.eventId() != null
                && evento.comercianteId() != null
                && evento.dataNegocio() != null
                && evento.valor() != null
                && evento.valor().signum() > 0
                && evento.tipo() != null;
    }

    @Override
    public String toString() {
        return "ErroEvento[" + acao + " retry=" + retryCount + " " + motivo + "]";
    }
}
