package com.verety.lancamentos.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * Corpo do {@code POST /api/v1/lancamentos} (openapi.yaml {@code LancamentoRequest}).
 *
 * <p>Tudo string no fio (dinheiro NUNCA number/float). {@code moeda} é opcional (default BRL).
 */
public record LancamentoRequest(
        @JsonProperty("comerciante_id")
        @NotBlank
        String comercianteId,

        @NotBlank
        String tipo,

        @NotBlank
        @Pattern(regexp = "^[0-9]{1,16}\\.[0-9]{2}$",
                message = "valor deve ser decimal positivo com exatamente 2 casas")
        String valor,

        @JsonProperty("moeda")
        String moeda,

        @JsonProperty("data_negocio")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate dataNegocio
) {
}
