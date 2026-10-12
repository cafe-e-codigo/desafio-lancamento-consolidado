package com.verety.consolidado.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Middleware JWT (item 1) e ownership por recurso: BOLA (item 2) do ADR-008.
 *
 * <p>Autentica o {@code Authorization: Bearer}, publica a identidade no {@code SecurityContext} com
 * as autoridades {@code SCOPE_*} (consumidas pela matriz de autorização do {@link SecurityConfig}) e
 * aplica a regra BOLA no caminho de leitura {@code /consolidado/{comerciante_id}}: claim
 * {@code merchant_id} &ne; path &rarr; <b>404</b> (não vaza existência). BOLA do corpo de escrita
 * (POST) é regra de domínio, via {@link MerchantOwnershipGuard}.
 */
public class JwtFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final Pattern CONSOLIDADO_PATH = Pattern.compile("/consolidado/([^/]+)");

    private final JwtVerifier verifier;
    private final SecurityAuditor auditor;
    private final ObjectMapper mapper;

    public JwtFilter(JwtVerifier verifier, SecurityAuditor auditor, ObjectMapper mapper) {
        this.verifier = verifier;
        this.auditor = auditor;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER.length()).trim();
        try {
            JwtVerifier.VeretyPrincipal principal = verifier.verify(token);
            var authorities = principal.scopes().stream()
                    .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                    .toList();
            var authentication = new UsernamePasswordAuthenticationToken(principal.subject(), null, authorities);
            authentication.setDetails(principal);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            String pathMerchant = pathMerchant(request);
            if (pathMerchant != null && principal.merchantId() != null
                    && !pathMerchant.equals(principal.merchantId())) {
                auditor.deny(principal.subject(), request, "bola_path_merchant_mismatch");
                Problems.write(response, mapper, 404, "nao-encontrado", "Recurso não encontrado",
                        "O recurso consultado não está disponível para este chamador.");
                return;
            }
            auditor.allow(principal.subject(), request);
            chain.doFilter(request, response);
        } catch (JwtVerifier.JwtVerificationException e) {
            auditor.deny("-", request, e.getMessage());
            Problems.write(response, mapper, 401, "nao-autenticado", "Não autenticado",
                    "Credencial ausente, inválida ou expirada.");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String pathMerchant(HttpServletRequest request) {
        Matcher matcher = CONSOLIDADO_PATH.matcher(request.getRequestURI());
        if (matcher.find()) {
            return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
        }
        return null;
    }

    static List<String> requiredScopes() {
        return List.of("lancamentos:write", "consolidado:read");
    }
}
