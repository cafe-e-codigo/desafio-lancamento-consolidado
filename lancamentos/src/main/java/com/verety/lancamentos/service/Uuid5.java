package com.verety.lancamentos.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * UUIDv5 determinístico (RFC 4122): SHA-1(namespace ‖ name).
 *
 * <p>Doutrina: ADR-005. O {@code event_id} é {@code UUIDv5(NS_VERETY_LANCAMENTO, Idempotency-Key)},
 * estável no relay, no consumidor e no redrive (dedup de origem).
 */
public final class Uuid5 {

    /**
     * Namespace canônico do Verety para lançamentos. Constante fixa: mudar o namespace
     * muda TODO {@code event_id} derivado (quebra de idempotência cross-versão).
     */
    public static final UUID NS_VERETY_LANCAMENTO =
            UUID.fromString("a5c1f3e2-7b9d-4e6a-8c1f-2d3b4a5c6d7e");

    private Uuid5() {
    }

    /** Deriva o UUIDv5 de {@code name} no {@code namespace}. */
    public static UUID of(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(bytesOf(namespace));
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] h = sha1.digest();
            h[6] = (byte) ((h[6] & 0x0f) | 0x50); // versão 5
            h[8] = (byte) ((h[8] & 0x3f) | 0x80); // variante RFC 4122
            long msb = 0L;
            long lsb = 0L;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (h[i] & 0xffL);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (h[i] & 0xffL);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponível na JVM", e);
        }
    }

    private static byte[] bytesOf(UUID uuid) {
        byte[] b = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (msb >>> (8 * (7 - i)));
            b[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return b;
    }
}
