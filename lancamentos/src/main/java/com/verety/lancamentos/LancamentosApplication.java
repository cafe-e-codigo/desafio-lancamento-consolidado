package com.verety.lancamentos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Serviço de Lançamentos (write-side, CQRS).
 *
 * <p>Bootstrap da aplicação. {@code @EnableScheduling} habilita o relay da outbox
 * ({@code com.verety.lancamentos.messaging.OutboxRelay}) que varre
 * {@code PENDING} fora do caminho da request (ADR-003).
 */
@SpringBootApplication
@EnableScheduling
public class LancamentosApplication {

    public static void main(String[] args) {
        SpringApplication.run(LancamentosApplication.class, args);
    }
}
