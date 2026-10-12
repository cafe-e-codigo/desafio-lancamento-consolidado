package com.verety.consolidado.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verety.common.error.ErroPadrao;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Emissão de erro RFC 9457 ({@code application/problem+json}) na borda de segurança. */
final class Problems {

    private Problems() {
    }

    static void write(HttpServletResponse response, ObjectMapper mapper, int status,
                      String slug, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), ErroPadrao.of(slug, title, status, detail));
    }
}
