package com.verety.lancamentos.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verificador de JWT próprio (sem {@code spring-boot-starter-oauth2-resource-server}, removido do
 * reactor por revisão de dependências: ver {@code pom.xml} do módulo).
 *
 * <p>Materializa o item 1 do ADR-008: valida {@code iss}/{@code aud}/{@code exp}/{@code alg} com
 * <b>allowlist de algoritmo</b> ({@code alg=none} proibido por construção: só HS256/RS256 entram na
 * allowlist) e extrai o {@code scope} e o {@code merchant_id} do chamador.
 *
 * <p>Assinatura: {@code HS256} (segredo HMAC por variável de ambiente, proxy local) ou {@code RS256}
 * (chave pública PEM, rotação por JWKS no Alvo via Cognito). O <b>segredo nunca é literal</b> no
 * código: vem de {@code VERETY_JWT_HMAC_SECRET} (env) e é tratado por nome de variável, nunca lido/colado
 * aqui (threat-model §Segredos).
 */
@Component
public class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);

    private final ObjectMapper mapper;
    private final String issuer;
    private final String audience;
    private final Set<String> allowedAlgorithms;
    private final byte[] hmacSecret;
    private final PublicKey rsaPublicKey;
    private final String scopeClaim;
    private final String merchantClaim;

    public JwtVerifier(
            ObjectMapper mapper,
            @Value("${verety.security.jwt.issuer:}") String issuer,
            @Value("${verety.security.jwt.audience:}") String audience,
            @Value("${verety.security.jwt.algorithms:HS256,RS256}") String algorithms,
            @Value("${verety.security.jwt.hmac-secret:}") String hmacSecret,
            @Value("${verety.security.jwt.public-key-pem:}") String publicKeyPem,
            @Value("${verety.security.jwt.scope-claim:scope}") String scopeClaim,
            @Value("${verety.security.jwt.merchant-claim:merchant_id}") String merchantClaim) {
        this.mapper = mapper;
        this.issuer = issuer == null ? "" : issuer.trim();
        this.audience = audience == null ? "" : audience.trim();
        this.allowedAlgorithms = new LinkedHashSet<>();
        for (String a : algorithms.split(",")) {
            if (!a.isBlank()) {
                this.allowedAlgorithms.add(a.trim());
            }
        }
        this.hmacSecret = (hmacSecret == null || hmacSecret.isBlank())
                ? new byte[0] : hmacSecret.getBytes(StandardCharsets.UTF_8);
        this.rsaPublicKey = parsePublicKey(publicKeyPem);
        this.scopeClaim = scopeClaim;
        this.merchantClaim = merchantClaim;
    }

    /** Identidade verificada extraída do token (não é PII: identificadores). */
    public record VeretyPrincipal(String subject, String merchantId, Set<String> scopes,
                                  List<String> audiences, Instant expiresAt) {
    }

    /** Falha de autenticação/validação do token. Nunca carrega o token (evita vazar credencial em log). */
    public static class JwtVerificationException extends RuntimeException {
        public JwtVerificationException(String message) {
            super(message);
        }
    }

    public VeretyPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtVerificationException("token ausente");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtVerificationException("formato JWT inválido");
        }
        Map<String, Object> header = decode(parts[0]);
        Object alg = header.get("alg");
        if (!(alg instanceof String algName) || !allowedAlgorithms.contains(algName)) {
            throw new JwtVerificationException("algoritmo não permitido");
        }
        verifySignature(algName, parts[0] + "." + parts[1], decodeBytes(parts[2]));

        Map<String, Object> claims = decode(parts[1]);
        checkExpiry(claims);
        checkNotBefore(claims);
        checkIssuer(claims);
        List<String> audiences = audiences(claims);
        checkAudience(audiences);

        Set<String> scopes = scopes(claims);
        Object merchant = claims.get(merchantClaim);
        String subject = claims.get("sub") == null ? null : claims.get("sub").toString();
        return new VeretyPrincipal(subject, merchant == null ? null : merchant.toString(),
                scopes, audiences, expiresAt(claims));
    }

    private void verifySignature(String alg, String signingInput, byte[] signature) {
        byte[] input = signingInput.getBytes(StandardCharsets.US_ASCII);
        try {
            boolean ok;
            if ("HS256".equals(alg)) {
                if (hmacSecret.length == 0) {
                    throw new JwtVerificationException("verificador HS256 não configurado (fail-closed)");
                }
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
                ok = MessageDigest.isEqual(mac.doFinal(input), signature);
            } else if ("RS256".equals(alg)) {
                if (rsaPublicKey == null) {
                    throw new JwtVerificationException("verificador RS256 não configurado (fail-closed)");
                }
                Signature rsa = Signature.getInstance("SHA256withRSA");
                rsa.initVerify(rsaPublicKey);
                rsa.update(input);
                ok = rsa.verify(signature);
            } else {
                throw new JwtVerificationException("algoritmo não suportado");
            }
            if (!ok) {
                throw new JwtVerificationException("assinatura inválida");
            }
        } catch (JwtVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtVerificationException("falha ao verificar assinatura");
        }
    }

    private void checkExpiry(Map<String, Object> claims) {
        Instant exp = expiresAt(claims);
        if (exp != null && Instant.now().isAfter(exp)) {
            throw new JwtVerificationException("token expirado");
        }
    }

    private void checkNotBefore(Map<String, Object> claims) {
        Object nbf = claims.get("nbf");
        if (nbf instanceof Number n && Instant.now().isBefore(Instant.ofEpochSecond(n.longValue()))) {
            throw new JwtVerificationException("token ainda não válido");
        }
    }

    private void checkIssuer(Map<String, Object> claims) {
        if (!issuer.isEmpty()) {
            Object iss = claims.get("iss");
            if (iss == null || !issuer.equals(iss.toString())) {
                throw new JwtVerificationException("emissor inválido");
            }
        }
    }

    private void checkAudience(List<String> audiences) {
        if (!audience.isEmpty() && !audiences.contains(audience)) {
            throw new JwtVerificationException("audiência inválida");
        }
    }

    private Instant expiresAt(Map<String, Object> claims) {
        Object exp = claims.get("exp");
        return (exp instanceof Number n) ? Instant.ofEpochSecond(n.longValue()) : null;
    }

    private List<String> audiences(Map<String, Object> claims) {
        List<String> out = new ArrayList<>();
        Object aud = claims.get("aud");
        if (aud instanceof String s) {
            out.add(s);
        } else if (aud instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
        }
        return out;
    }

    private Set<String> scopes(Map<String, Object> claims) {
        Set<String> out = new LinkedHashSet<>();
        Object sc = claims.get(scopeClaim);
        if (sc instanceof String s) {
            out.addAll(Arrays.asList(s.trim().split("\\s+")));
        } else if (sc instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
        }
        out.remove("");
        return Collections.unmodifiableSet(out);
    }

    private Map<String, Object> decode(String base64Url) {
        try {
            return mapper.readValue(decodeBytes(base64Url), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new JwtVerificationException("token malformado");
        }
    }

    private byte[] decodeBytes(String base64Url) {
        try {
            return Base64.getUrlDecoder().decode(base64Url);
        } catch (Exception e) {
            throw new JwtVerificationException("token malformado");
        }
    }

    private PublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            String body = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            log.warn("event=security.config detail=chave_publica_rs256_invalida");
            return null;
        }
    }
}
