package com.verety.consolidado.observability;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Hit/miss <b>real</b> do cache Redis (ADR-009 item 5) lido do {@code INFO stats} do servidor
 * ({@code keyspace_hits}/{@code keyspace_misses}). Fonte é a própria instância, não um contador
 * otimista da app. À prova de falha: Redis fora do ar degrada para 0.
 */
@Component
public class CacheStats {

    private final RedisConnectionFactory connectionFactory;

    public CacheStats(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public long hits() {
        return stat("keyspace_hits");
    }

    public long misses() {
        return stat("keyspace_misses");
    }

    public long expired() {
        return stat("expired_keys");
    }

    public long evicted() {
        return stat("evicted_keys");
    }

    /** hits / (hits + misses); {@code -1} quando não houve acessos (evita divisão por zero no scrape). */
    public double hitRatio() {
        long h = hits();
        long m = misses();
        long total = h + m;
        return total == 0 ? -1d : (double) h / (double) total;
    }

    private long stat(String key) {
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            Properties info = connection.info("stats");
            String value = info.getProperty(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // best-effort: fecha a conexão do pool
                }
            }
        }
    }
}
