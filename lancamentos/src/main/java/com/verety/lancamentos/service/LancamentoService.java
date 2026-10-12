package com.verety.lancamentos.service;

import com.verety.common.dto.LancamentoCriado;
import com.verety.common.money.Money;
import com.verety.lancamentos.persistence.Lancamento;
import com.verety.lancamentos.persistence.LancamentoRepository;
import com.verety.lancamentos.persistence.OutboxEvent;
import com.verety.lancamentos.persistence.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Caso de uso de escrita (write-side).
 *
 * <p><b>Uma transação, dois efeitos:</b> INSERT em {@code lancamentos.lancamento} +
 * INSERT em {@code lancamentos.outbox} (PENDING) no MESMO commit (ADR-003). O {@code 201}
 * sai no COMMIT; o broker não está no caminho da request (RNF#1).
 *
 * <p><b>Idempotência de origem</b> (ADR-005): {@code event_id = UUIDv5(NS_VERETY_LANCAMENTO,
 * Idempotency-Key)}. Mesma key + mesmo corpo ⇒ replay (200, sem novo efeito); mesma key +
 * corpo diferente ⇒ {@link IdempotencyConflictException} (409).
 */
@Service
public class LancamentoService {

    private static final String TIPO_CREDITO = "CREDITO";
    private static final String TIPO_DEBITO = "DEBITO";
    private static final Set<String> TIPOS = Set.of(TIPO_CREDITO, TIPO_DEBITO);
    private static final String MOEDA_BRL = "BRL";
    private static final String EVENT_TYPE = "LancamentoCriado";
    private static final String STATUS_PENDENTE = "PENDING";

    private final LancamentoRepository lancamentos;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public LancamentoService(LancamentoRepository lancamentos, OutboxRepository outbox,
                             ObjectMapper objectMapper) {
        this.lancamentos = lancamentos;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /** PUBLICADO se a outbox já publicou o evento; PENDENTE caso contrário. */
    private static final String STATUS_PUBLICADO = "PUBLICADO";

    /** Resultado de uma criação: o lançamento persistido + se foi criação nova (201) ou replay (200). */
    public record Criacao(Lancamento lancamento, boolean novo, String status) {
    }

    @Transactional
    public Criacao criar(String idempotencyKey, ComandoLancamento cmd) {
        validarDominio(cmd);

        UUID eventId = Uuid5.of(Uuid5.NS_VERETY_LANCAMENTO, idempotencyKey);
        UUID comercianteId = cmd.comercianteId();
        LocalDate data = cmd.dataNegocio();
        String tipo = cmd.tipo().toUpperCase();
        BigDecimal valor = Money.of(cmd.valor()).toBigDecimal();

        Optional<Lancamento> existente = lancamentos.findFirstByTransactionId(eventId);
        if (existente.isPresent()) {
            Lancamento anterior = existente.get();
            if (!mesmoConteudo(anterior, comercianteId, data, tipo, valor)) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key já usada com corpo diferente (event_id=" + eventId + ").");
            }
            return new Criacao(anterior, false, statusDoEvento(eventId));
        }

        Lancamento lancamento = new Lancamento();
        lancamento.setTransactionId(eventId);
        lancamento.setComercianteId(comercianteId);
        lancamento.setData(data);
        lancamento.setTipo(tipo);
        lancamento.setValor(valor);
        lancamento.setCriadoEm(Instant.now());
        Lancamento salvo = lancamentos.saveAndFlush(lancamento);

        LancamentoCriado evento = new LancamentoCriado(
                eventId,
                salvo.getId(),
                comercianteId,
                tipo,
                valor,
                MOEDA_BRL,
                data,
                UUID.randomUUID());
        outbox.save(novaOutbox(salvo, evento));

        return new Criacao(salvo, true, STATUS_PENDENTE);
    }

    private String statusDoEvento(UUID eventId) {
        return outbox.buscarPorEventId(eventId)
                .filter(o -> STATUS_PUBLICADO.equals(o.getStatus()))
                .map(o -> STATUS_PUBLICADO)
                .orElse(STATUS_PENDENTE);
    }

    private OutboxEvent novaOutbox(Lancamento salvo, LancamentoCriado evento) {
        OutboxEvent o = new OutboxEvent();
        o.setAggregateId(salvo.getId());
        o.setEventId(evento.eventId());
        o.setEventType(EVENT_TYPE);
        o.setPayload(serializar(evento));
        o.setStatus(STATUS_PENDENTE);
        o.setAttempts(0);
        Instant agora = Instant.now();
        o.setNextAttempt(agora);
        o.setCreatedAt(agora);
        return o;
    }

    private String serializar(LancamentoCriado evento) {
        try {
            return objectMapper.writeValueAsString(evento);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o evento de outbox", e);
        }
    }

    private void validarDominio(ComandoLancamento cmd) {
        if (cmd.tipo() == null || !TIPOS.contains(cmd.tipo().toUpperCase())) {
            throw new DominioInvalidoException("tipo deve ser CREDITO ou DEBITO.");
        }
        if (cmd.moeda() != null && !MOEDA_BRL.equalsIgnoreCase(cmd.moeda())) {
            throw new DominioInvalidoException("moeda deve ser BRL.");
        }
        if (cmd.valor() == null) {
            throw new DominioInvalidoException("valor é obrigatório.");
        }
        BigDecimal valor;
        try {
            valor = Money.of(cmd.valor()).toBigDecimal();
        } catch (NumberFormatException e) {
            throw new DominioInvalidoException("valor não é um decimal válido com 2 casas.");
        }
        if (valor.signum() <= 0) {
            throw new DominioInvalidoException("valor deve ser maior que zero.");
        }
        if (valor.scale() > Money.SCALE) {
            throw new DominioInvalidoException("valor deve ter no máximo 2 casas decimais.");
        }
        if (cmd.dataNegocio() == null) {
            throw new DominioInvalidoException("data_negocio é obrigatória.");
        }
    }

    private boolean mesmoConteudo(Lancamento anterior, UUID comercianteId, LocalDate data,
                                  String tipo, BigDecimal valor) {
        return anterior.getComercianteId().equals(comercianteId)
                && anterior.getData().equals(data)
                && anterior.getTipo().equals(tipo)
                && anterior.getValor().compareTo(valor) == 0;
    }
}
