package com.verety.consolidado.web;

import com.verety.consolidado.service.ConsolidadoView;
import com.verety.consolidado.service.SaldoQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fachada HTTP do read-side: {@code GET /api/v1/consolidado/{comerciante_id}?data=} (openapi.yaml).
 *
 * <p>{@code data} é de NEGÓCIO e opcional: o default é HOJE na TZ {@code America/Sao_Paulo}
 * (ADR-012 §Decisão item 4).
 */
@RestController
@RequestMapping("/api/v1/consolidado")
public class ConsolidadoController {

    private final SaldoQueryService service;

    public ConsolidadoController(SaldoQueryService service) {
        this.service = service;
    }

    @GetMapping("/{comerciante_id}")
    public ResponseEntity<ConsolidadoResponse> obter(
            @PathVariable("comerciante_id") String comercianteId,
            @RequestParam(value = "data", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {

        UUID id = parseComerciante(comercianteId);
        LocalDate dia = data != null ? data : LocalDate.now(SaldoQueryService.TZ_NEGOCIO);

        ConsolidadoView view = service.consultar(id, dia);
        return ResponseEntity.ok(new ConsolidadoResponse(
                view.comercianteId().toString(),
                view.data(),
                view.saldo().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                view.moeda(),
                view.atualizadoEm(),
                view.status()));
    }

    private static UUID parseComerciante(String valor) {
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            throw new com.verety.consolidado.service.RecursoNaoEncontradoException(
                    "comerciante_id inválido: " + valor + ".");
        }
    }
}
