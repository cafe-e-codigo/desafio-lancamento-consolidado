package com.verety.lancamentos.web;

import com.verety.lancamentos.persistence.Lancamento;
import com.verety.lancamentos.service.ComandoLancamento;
import com.verety.lancamentos.service.DominioInvalidoException;
import com.verety.lancamentos.service.LancamentoService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fachada HTTP do write-side: {@code POST /api/v1/lancamentos} (openapi.yaml).
 *
 * <p>{@code Idempotency-Key} é obrigatória. {@code 201 Created} + {@code Location} na criação;
 * {@code 200 OK} no replay idempotente (mesma key + mesmo corpo). Erros viram RFC 9457
 * ({@link ApiExceptionHandler}).
 */
@RestController
@RequestMapping("/api/v1/lancamentos")
public class LancamentoController {

    /** Mesmo padrão do contrato ({@code components.parameters.IdempotencyKey}). */
    private static final Pattern CHAVE = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final LancamentoService service;

    public LancamentoController(LancamentoService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<LancamentoResponse> criar(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LancamentoRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CabecalhoAusenteException("Cabeçalho Idempotency-Key é obrigatório.");
        }
        if (!CHAVE.matcher(idempotencyKey).matches()) {
            throw new CabecalhoAusenteException(
                    "Idempotency-Key inválida: use 8..128 caracteres de [A-Za-z0-9._:-].");
        }

        ComandoLancamento comando = new ComandoLancamento(
                parseComerciante(request.comercianteId()),
                request.tipo(),
                request.valor(),
                request.moeda(),
                request.dataNegocio());

        LancamentoService.Criacao criacao = service.criar(idempotencyKey, comando);
        LancamentoResponse body = paraResposta(criacao.lancamento(), criacao.status());

        if (criacao.novo()) {
            URI location = URI.create("/api/v1/lancamentos/" + criacao.lancamento().getId());
            return ResponseEntity.created(location).body(body);
        }
        return ResponseEntity.ok(body);
    }

    private static UUID parseComerciante(String valor) {
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            // O DDL canônico define comerciante_id uuid; o exemplo "com-1" do contrato não é válido.
            throw new DominioInvalidoException("comerciante_id deve ser um UUID.");
        }
    }

    private static LancamentoResponse paraResposta(Lancamento l, String status) {
        return new LancamentoResponse(
                String.valueOf(l.getId()),
                l.getTransactionId().toString(),
                l.getComercianteId().toString(),
                l.getTipo(),
                decimal(l.getValor()),
                "BRL",
                l.getData(),
                status,
                l.getCriadoEm());
    }

    private static String decimal(BigDecimal valor) {
        return valor.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
