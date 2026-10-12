package com.verety.consolidado.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres 16 compartilhado por TODA a suíte do read-side (1 container por JVM do módulo).
 *
 * <p>Iniciado num inicializador estático (não via {@code @Container}) de propósito: o contexto
 * Spring precisa do JDBC URL em {@code @DynamicPropertySource}, avaliado ANTES do ciclo do
 * extension do JUnit. Um shutdown hook para o container no fim da JVM.
 *
 * <p><b>O container é a fronteira externa:</b> o oráculo conta o
 * efeito no Postgres de verdade, nunca em métrica interna da app ({@code writer == validator}).
 */
public final class SharedPostgres {

    public static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("verety")
                    .withUsername("verety")
                    .withPassword("verety");

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    private SharedPostgres() {
    }
}
