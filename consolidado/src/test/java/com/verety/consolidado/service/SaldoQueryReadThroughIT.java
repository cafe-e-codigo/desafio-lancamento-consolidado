package com.verety.consolidado.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verety.consolidado.persistence.SaldoDiario;
import com.verety.consolidado.persistence.SaldoDiarioRepository;
import com.verety.consolidado.support.SharedRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Read-through Redis + <b>single-flight</b> (ADR-006), contra <b>Redis 7 real</b>.
 *
 * <p>Oráculo da manada: no <i>cache-miss</i> com N threads concorrentes, o repositório do Postgres
 * é consultado <b>uma única vez</b> (as demais releem do cache após o lock). Contamos a chamada
 * <b>no repositório</b> (fronteira do banco), não em métrica interna.
 *
 * <p>TTL e expiração observados no Redis real: {@code TTL ∈ (0, 30s]}.
 */
class SaldoQueryReadThroughIT {

    private static final UUID COMERCIANTE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate DIA = LocalDate.of(2026, 10, 7);
    private static final String CHAVE = "consolidado:" + COMERCIANTE + ":" + DIA;

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void subirRedis() {
        factory = new LettuceConnectionFactory(SharedRedis.host(), SharedRedis.port());
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void pararRedis() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("N threads no miss ⇒ 1 só query ao Postgres (single-flight)")
    void c5_singleFlightUmaQueryNoMiss() throws Exception {
        redis.delete(CHAVE);
        AtomicInteger queries = new AtomicInteger();
        SaldoDiarioRepository repo = repoQueConta(queries, "123.45");
        SaldoQueryService service = new SaldoQueryService(repo, redis, mapper());

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<ConsolidadoView>> futuros = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return service.consultar(COMERCIANTE, DIA);
            }));
        }
        largada.countDown();

        for (Future<ConsolidadoView> f : futuros) {
            assertThat(f.get(30, TimeUnit.SECONDS).saldo()).isEqualByComparingTo("123.45");
        }
        pool.shutdownNow();

        assertThat(queries.get())
                .as("single-flight: 1 query ao Postgres para 32 leitores concorrentes")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("read-through serve do cache e TTL fica em (0,30s]")
    void c5_readThroughServeDoCacheComTtl() {
        redis.delete(CHAVE);
        AtomicInteger queries = new AtomicInteger();
        SaldoQueryService service = new SaldoQueryService(repoQueConta(queries, "9.99"), redis, mapper());

        service.consultar(COMERCIANTE, DIA); // miss
        service.consultar(COMERCIANTE, DIA); // hit
        service.consultar(COMERCIANTE, DIA); // hit

        assertThat(queries.get()).as("só o 1º GET vai ao Postgres").isEqualTo(1);
        Long ttl = redis.getExpire(CHAVE, TimeUnit.SECONDS);
        assertThat(ttl).as("TTL existe").isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(30L);
        assertThat(redis.opsForValue().get(CHAVE)).as("payload serializado no Redis").isNotBlank();
    }

    @Test
    @DisplayName("invalidação: DEL força reconstrução (read-through, não stale)")
    void c6_delForcaReconstrucao() {
        redis.delete(CHAVE);
        AtomicInteger queries = new AtomicInteger();
        SaldoQueryService service = new SaldoQueryService(repoQueConta(queries, "5.00"), redis, mapper());

        service.consultar(COMERCIANTE, DIA);
        redis.delete(CHAVE); // eviction / invalidação
        service.consultar(COMERCIANTE, DIA);

        assertThat(queries.get()).as("após DEL, uma nova query reconstrói o cache").isEqualTo(2);
    }

    // -------------------------------------------------------------- helpers

    private static SaldoDiarioRepository repoQueConta(AtomicInteger contador, String valor) {
        SaldoDiarioRepository repo = mock(SaldoDiarioRepository.class);
        SaldoDiario saldo = new SaldoDiario();
        saldo.setComercianteId(COMERCIANTE);
        saldo.setData(DIA);
        saldo.setValor(new BigDecimal(valor));
        saldo.setAtualizadoEm(Instant.now());
        when(repo.buscar(any(), any())).thenAnswer(inv -> {
            contador.incrementAndGet();
            return Optional.of(saldo);
        });
        return repo;
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
