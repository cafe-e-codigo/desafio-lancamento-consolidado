package com.verety.lancamentos.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Segurança de consumo do serviço de Lançamentos (ADR-008, itens 1/2/4).
 *
 * <p><b>Stateless</b>: cada requisição é autenticada pelo {@link JwtFilter} (Bearer JWT), sem sessão
 * nem CSRF (API sem cookies). Autorização por endpoint:
 * {@code POST /api/v1/lancamentos} exige {@code SCOPE_lancamentos:write};
 * {@code GET /api/v1/consolidado/**} exige {@code SCOPE_consolidado:read}; o resto exige autenticação.
 *
 * <p>No perfil {@code test} (Testcontainers) o harness não injeta token: a cadeia libera os
 * endpoints (requisito explícito da fase), mantendo o filtro fora para não falhar por segredo ausente.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtFilter veretyJwtFilter(JwtVerifier verifier, SecurityAuditor auditor, ObjectMapper mapper) {
        return new JwtFilter(verifier, auditor, mapper);
    }

    /**
     * Impede a auto-registração do servlet-container para o {@link JwtFilter}: ele roda dentro da
     * cadeia do Spring Security (após o contexto de segurança), não como filtro global solto,
     * senão executaria duas vezes e fora de ordem.
     */
    @Bean
    public FilterRegistrationBean<JwtFilter> veretyJwtFilterRegistration(JwtFilter filter) {
        FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Profile("!test")
    public SecurityFilterChain veretySecurityFilterChain(
            HttpSecurity http,
            JwtFilter jwtFilter,
            @Value("${verety.security.test-bypass:false}") boolean testBypass) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        if (testBypass) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> auth
                    // Sondas e raspagem de métricas são infraestrutura, não dado de comerciante:
                    // abertas para o scraper (sem PII: as métricas são contadores/gauges de outbox).
                    // No Alvo (ADR-008 item 6 / ADR-009) o managent fica em porta privada atrás do
                    // edge; aqui (perfil observability local) o /actuator/metrics/** é raspável.
                    .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/metrics/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/lancamentos").hasAuthority("SCOPE_lancamentos:write")
                    .requestMatchers(HttpMethod.GET, "/api/v1/consolidado/**").hasAuthority("SCOPE_consolidado:read")
                    .anyRequest().authenticated());
        }
        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Profile("test")
    public SecurityFilterChain veretyTestSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
