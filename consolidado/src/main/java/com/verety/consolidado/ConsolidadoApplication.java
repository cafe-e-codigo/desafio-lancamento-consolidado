package com.verety.consolidado;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Serviço de Consolidado (read-side, CQRS).
 *
 * <p>Bootstrap do serviço de Consolidado (estrutura multi-módulo). O conteúdo de negócio
 * (consumidor AMQP, ledger {@code processed_event}, job de rollup, cache read-through + single-flight)
 * é implementado nos pacotes
 * deste módulo.
 *
 * <p>Objetivo desta classe: permitir que {@code mvn package} produza o executável Spring Boot.
 * Não contém regra de negócio.
 */
@SpringBootApplication
public class ConsolidadoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsolidadoApplication.class, args);
    }
}
