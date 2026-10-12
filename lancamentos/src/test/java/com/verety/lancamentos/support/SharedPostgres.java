package com.verety.lancamentos.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Postgres 16 compartilhado pelos testes do write-side (um por JVM do módulo). */
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
