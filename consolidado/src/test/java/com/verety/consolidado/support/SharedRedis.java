package com.verety.consolidado.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis 7 compartilhado pelos testes de cache do read-side. Um container por JVM.
 *
 * <p>Fonte de verdade continua sendo o Postgres; aqui exercitamos <b>read-through + single-flight</b>
 * contra um Redis real (TTL/expiração de verdade), não um dublê que esconderia bugs de expiração.
 */
public final class SharedRedis {

    public static final GenericContainer<?> CONTAINER =
            new GenericContainer<>(DockerImageName.parse("redis:7"))
                    .withExposedPorts(6379);

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    private SharedRedis() {
    }

    public static String host() {
        return CONTAINER.getHost();
    }

    public static int port() {
        return CONTAINER.getMappedPort(6379);
    }
}
