package com.verety.consolidado.messaging;

import com.verety.common.dto.LancamentoCriado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Taxonomia transiente × veneno do consumidor (ADR-007). Puro (sem container):
 * cada caminho é determinístico e o oráculo é a própria classificação.
 *
 * <p>Controla a diferença que evita queimar entrega VÁLIDA: transiente → retry (com
 * {@code x-retry-count++}); veneno → DLQ na 1ª vez, sem consumir retry.
 */
class ErroEventoTaxonomiaTest {

    private static final LancamentoCriado VALIDO = new LancamentoCriado(
            UUID.randomUUID(), 1L, UUID.randomUUID(), "CREDITO",
            new BigDecimal("10.00"), "BRL", LocalDate.of(2026, 10, 8), UUID.randomUUID());

    @Test
    @DisplayName("transiente (infra) ⇒ RETRY com x-retry-count incrementado, nunca DLQ")
    void transienteVaiParaRetry() {
        ErroEvento erro = ErroEvento.classificar(new RuntimeException("db timeout"), 0, VALIDO);

        assertThat(erro.acao()).isEqualTo(ErroEvento.Acao.RETRY);
        assertThat(erro.retryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("veneno (payload inválido) ⇒ DLQ na 1ª vez, sem consumir retry")
    void venenoVaiParaDlqNaPrimeiraVez() {
        ErroEvento erro = ErroEvento.classificar(new IllegalArgumentException("valor inválido"), 0, VALIDO);

        assertThat(erro.acao()).isEqualTo(ErroEvento.Acao.DLQ);
        assertThat(erro.retryCount()).as("não incrementa").isZero();
    }

    @Test
    @DisplayName("evento fora do contrato (sem event_id) ⇒ DLQ mesmo em falha transiente")
    void eventoInvalidoEhVeneno() {
        LancamentoCriado semId = new LancamentoCriado(null, 1L, UUID.randomUUID(), "CREDITO",
                new BigDecimal("10.00"), "BRL", LocalDate.of(2026, 10, 8), UUID.randomUUID());

        ErroEvento erro = ErroEvento.classificar(new RuntimeException("transiente"), 0, semId);

        assertThat(erro.acao()).isEqualTo(ErroEvento.Acao.DLQ);
    }

    @Test
    @DisplayName("transiente no teto (MAX_RETRIES) ⇒ DLQ")
    void transienteEsgotadoVaiParaDlq() {
        ErroEvento erro = ErroEvento.classificar(
                new RuntimeException("db timeout"), ErroEvento.MAX_RETRIES, VALIDO);

        assertThat(erro.acao()).isEqualTo(ErroEvento.Acao.DLQ);
    }

    @Test
    @DisplayName("controle · transiente NÃO é classificado como veneno na 1ª falha")
    void transienteNaoEhVeneno() {
        ErroEvento erro = ErroEvento.classificar(new RuntimeException("lock"), 0, VALIDO);
        assertThat(erro.motivo()).doesNotContain("VENENO");
    }
}
