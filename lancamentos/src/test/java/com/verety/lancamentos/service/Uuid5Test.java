package com.verety.lancamentos.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotência de borda: a Idempotency-Key deriva um {@code event_id} DETERMINÍSTICO
 * (UUIDv5, ADR-005). Puro. Se a derivação não fosse estável, o retry do cliente viraria crédito
 * duplicado; se mudasse entre versões, o dedup do consumidor quebraria.
 */
class Uuid5Test {

    @Test
    @DisplayName("mesma Idempotency-Key ⇒ mesmo event_id (UUIDv5 estável)")
    void determinismo() {
        UUID a = Uuid5.of(Uuid5.NS_VERETY_LANCAMENTO, "pedido-42");
        UUID b = Uuid5.of(Uuid5.NS_VERETY_LANCAMENTO, "pedido-42");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("keys distintas ⇒ event_ids distintos")
    void unicidade() {
        UUID a = Uuid5.of(Uuid5.NS_VERETY_LANCAMENTO, "pedido-1");
        UUID b = Uuid5.of(Uuid5.NS_VERETY_LANCAMENTO, "pedido-2");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("é UUID versão 5 (SHA-1 name-based)")
    void versao5() {
        UUID id = Uuid5.of(Uuid5.NS_VERETY_LANCAMENTO, "x");
        assertThat(id.version()).isEqualTo(5);
    }
}
