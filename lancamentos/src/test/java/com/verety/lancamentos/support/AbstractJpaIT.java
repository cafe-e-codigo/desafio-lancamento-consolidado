package com.verety.lancamentos.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de integração do write-side contra Postgres 16 real (Testcontainers), com o DDL
 * canônico aplicado pela migração Flyway {@code db/migration/V1__lancamentos.sql}.
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
