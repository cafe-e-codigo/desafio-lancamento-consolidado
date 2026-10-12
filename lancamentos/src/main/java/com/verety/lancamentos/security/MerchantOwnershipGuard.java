package com.verety.lancamentos.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Regra de ownership (BOLA) na borda do domínio de escrita (item 2 do ADR-008): a claim
 * {@code merchant_id} do token deve ser igual ao {@code comerciante_id} do recurso. O eixo Backend
 * invoca este guard no serviço/controller; o corpo do serviço não é alterado aqui.
 */
@Component
public class MerchantOwnershipGuard {

    private final SecurityAuditor auditor;

    public MerchantOwnershipGuard(SecurityAuditor auditor) {
        this.auditor = auditor;
    }

    public void assertCanAccess(String comercianteId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof JwtVerifier.VeretyPrincipal principal)) {
            auditor.deny("-", null, "sem_autenticacao");
            throw new MerchantAccessDeniedException("requisição sem identidade verificada");
        }
        if (principal.merchantId() == null || !principal.merchantId().equals(comercianteId)) {
            auditor.deny(principal.subject(), null, "bola_domain_merchant_mismatch");
            throw new MerchantAccessDeniedException("merchant_id do token difere do recurso");
        }
    }
}
