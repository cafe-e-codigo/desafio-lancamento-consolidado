package com.verety.consolidado.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de integração do read-side: fatia JPA contra Postgres 16 real (Testcontainers)
 * com o DDL aplicado pela <b>migração Flyway canônica</b> {@code db/migration/V1__consolidado.sql}.
 *
 * <p>Por que fatia JPA e não contexto completo: Rabbit/Redis não são necessários para
 * (dedup/rollup) e o contexto completo ligaria o {@code @RabbitListener}. O que importa aqui é o
 * <b>comportamento no Postgres</b> (ON CONFLICT, soma assinada, invariante), que é a fronteira real.
 *
 * <p>Flyway é rodado à mão em {@code @BeforeAll} porque a fatia {@code @DataJpaTest} não traz a
 * auto-configuração de migração, e {@code ddl-auto=none} garante que o Hibernate NÃO tenta criar
 * as tabelas particionadas.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractJpaIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres.CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres.CONTAINER::getUsername);
        registry.add("spring.datasource.password", SharedPostgres.CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", SharedPostgres.CONTAINER::getDriverClassName);
    }

    @BeforeAll
    static void aplicarMigracao() {
        Flyway.configure()
                .dataSource(SharedPostgres.CONTAINER.getJdbcUrl(),
                            SharedPostgres.CONTAINER.getUsername(),
                            SharedPostgres.CONTAINER.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
