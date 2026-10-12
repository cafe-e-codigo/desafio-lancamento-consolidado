package com.verety.consolidado.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Auditoria por chamador (item 7 do ADR-008): log estruturado com caller/rota/decisão allow-deny e o
 * {@code correlation_id} do ADR-009 (lido do MDC, preenchido pelo CorrelationIdFilter do eixo
 * observability). Não registra token nem payload, só identificadores (anti-PII, ADR-010).
 */
@Component
public class SecurityAuditor {

    private static final Logger log = LoggerFactory.getLogger("verety.audit.security");
    private static final String MDC_KEY = "correlationId";

    public void allow(String caller, HttpServletRequest request) {
        record(caller, request, "allow", null);
    }

    public void deny(String caller, HttpServletRequest request, String reason) {
        record(caller, request, "deny", reason);
    }

    private void record(String caller, HttpServletRequest request, String decision, String reason) {
        String method = request == null ? "-" : request.getMethod();
        String route = request == null ? "-" : request.getRequestURI();
        log.info("event=security.decision caller={} method={} route={} decision={} reason={} correlation_id={}",
                caller == null ? "-" : caller, method, route, decision, reason == null ? "-" : reason,
                MDC.get(MDC_KEY));
    }
}
