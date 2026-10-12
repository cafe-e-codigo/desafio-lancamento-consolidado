package com.verety.consolidado.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verety.consolidado.persistence.SaldoDiario;
import com.verety.consolidado.persistence.SaldoDiarioRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Leitura do consolidado: <b>read-through</b> do Redis com <b>single-flight DISTRIBUÍDO</b> e
 * <b>fallback ao Postgres</b> (ADR-006 / RNF#2).
 *
 * <p>Postgres ({@code consolidado.saldo_diario}) é a fonte de verdade; o Redis é cache derivado.
 * No <i>miss</i>, uma única instância por chave reconstrói a entrada, as demais aguardam e
 * releem, evitando <i>thundering herd</i> mesmo com <b>N réplicas</b> (o mecanismo é o
 * {@code SET key NX PX}, não um lock intra-JVM).
 *
 * <p>Se o Redis estiver indisponível, ou a espera curta esgotar, a leitura degrada para o
 * Postgres (o cache nunca é caminho de verdade nem ponto único de falha).
 */
@Service
public class SaldoQueryService implements SaldoCacheEvictor {

    public static final ZoneId TZ_NEGOCIO = ZoneId.of("America/Sao_Paulo");
    static final String MOEDA = "BRL";
    private static final Duration TTL_CACHE = Duration.ofSeconds(30);
    private static final Duration TTL_LOCK = Duration.ofSeconds(5);
    private static final long ESPERA_MAX_MS = 2_000L;
    private static final long POLL_MS = 20L;
    private static final String SUFIXO_LOCK = ":lock";
    private static final String STATUS_ABERTO = "aberto";
    private static final String STATUS_FECHADO = "fechado";

    /** Token único por thread para liberar o lock com segurança (evita ABA entre donos). */
    private static final ThreadLocal<String> TOKEN = ThreadLocal.withInitial(
            () -> UUID.randomUUID().toString());

    /** Compare-and-delete do lock: só libera se ainda formos o dono (evita ABA de TTL expirado). */
    private static final DefaultRedisScript<Long> SCRIPT_SOLTA_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    private final SaldoDiarioRepository saldos;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SaldoQueryService(SaldoDiarioRepository saldos, StringRedisTemplate redis,
                             ObjectMapper objectMapper) {
        this.saldos = saldos;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ConsolidadoView consultar(UUID comercianteId, LocalDate data) {
        String chave = chave(comercianteId, data);

        ConsolidadoView cache = lerCache(chave);
        if (cache != null) {
            return cache;
        }

        Boolean dono = tentarAdquirirLock(chave);
        if (Boolean.TRUE.equals(dono)) {
            String token = TOKEN.get();
            try {
                // Double-check: enquanto esperávamos o lock, outra instância pode ter reconstruído.
                ConsolidadoView rechecado = lerCache(chave);
                if (rechecado != null) {
                    return rechecado;
                }
                ConsolidadoView reconstruido = lerDoPostgres(comercianteId, data);
                gravarCache(chave, reconstruido);
                return reconstruido;
            } finally {
                soltarLock(chave, token);
            }
        }

        if (Boolean.FALSE.equals(dono)) {
            // Não-dono: espera curta pelo cache reconstruído pelo dono (single-flight distribuído).
            ConsolidadoView aguardado = aguardarCache(chave, ESPERA_MAX_MS);
            if (aguardado != null) {
                return aguardado;
            }
        }

        // Redis indisponível ou espera esgotada: fallback direto ao Postgres (sem gravar cache).
        return lerDoPostgres(comercianteId, data);
    }

    /**
     * Invalida a entrada de cache de {@code (comerciante, data)}. Chamado pelo rollup
     * ({@link ConsolidacaoService}) APÓS o commit, sem isso o dado ficaria {@code TTL-bound}.
     * Cache é opcional: falha não propaga.
     */
    public void evict(UUID comercianteId, LocalDate data) {
        try {
            redis.delete(chave(comercianteId, data));
        } catch (Exception e) {
            // Cache descartável: a verdade está no Postgres.
        }
    }

    // ------------------------------------------------------------------ cache

    private ConsolidadoView lerCache(String chave) {
        try {
            String json = redis.opsForValue().get(chave);
            return json == null ? null : objectMapper.readValue(json, ConsolidadoView.class);
        } catch (Exception e) {
            // Redis fora do ar / payload corrompido: trata como miss e cai no Postgres.
            return null;
        }
    }

    private void gravarCache(String chave, ConsolidadoView view) {
        try {
            redis.opsForValue().set(chave, objectMapper.writeValueAsString(view), TTL_CACHE);
        } catch (Exception e) {
            // Cache é opcional: falha de escrita não pode falhar a leitura.
        }
    }

    private ConsolidadoView lerDoPostgres(UUID comercianteId, LocalDate data) {
        SaldoDiario saldo = saldos.buscar(comercianteId, data)
                .orElseThrow(() -> new RecursoNaoEncontradoException(
                        "Sem saldo consolidado para " + comercianteId + " em " + data + "."));
        return new ConsolidadoView(
                saldo.getComercianteId(),
                saldo.getData(),
                normalizar(saldo.getValor()),
                MOEDA,
                saldo.getAtualizadoEm(),
                status(saldo.getData()));
    }

    // ------------------------------------------------------------------ lock distribuído

    /** @return {@code TRUE} se adquiriu, {@code FALSE} se outro dono detém, {@code null} se o Redis falhou. */
    private Boolean tentarAdquirirLock(String chave) {
        try {
            return redis.opsForValue().setIfAbsent(chave + SUFIXO_LOCK, TOKEN.get(), TTL_LOCK);
        } catch (Exception e) {
            return null;
        }
    }

    private void soltarLock(String chave, String token) {
        try {
            redis.execute(SCRIPT_SOLTA_LOCK, List.of(chave + SUFIXO_LOCK), token);
        } catch (Exception e) {
            // O TTL do lock cobre a liberação perdida.
        }
    }

    private ConsolidadoView aguardarCache(String chave, long esperaMaxMs) {
        long limite = System.currentTimeMillis() + esperaMaxMs;
        do {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            ConsolidadoView view = lerCache(chave);
            if (view != null) {
                return view;
            }
        } while (System.currentTimeMillis() < limite);
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static String status(LocalDate data) {
        return data.isBefore(LocalDate.now(TZ_NEGOCIO)) ? STATUS_FECHADO : STATUS_ABERTO;
    }

    private static BigDecimal normalizar(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO.setScale(2) : valor.setScale(2);
    }

    private static String chave(UUID comercianteId, LocalDate data) {
        return "consolidado:" + comercianteId + ":" + data;
    }
}
