package com.verety.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Dinheiro exato em {@link BigDecimal} com escala 2 ({@code numeric(18,2)}).
 *
 * <p>Regra (RF02 · ADR-004): jamais usar {@code float}/{@code double}/{@code INCRBYFLOAT}
 * para valores monetários. Toda conversão é normalizada para escala 2 com {@link RoundingMode#HALF_UP}.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, ROUNDING));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    /** Cria a partir de um valor arbitrário, normalizando à escala 2. */
    public static Money of(BigDecimal value) {
        Objects.requireNonNull(value, "valor");
        return new Money(value.setScale(SCALE, ROUNDING));
    }

    /** Cria a partir de uma representação decimal textual (ex.: {@code "150.00"}). */
    public static Money of(String value) {
        Objects.requireNonNull(value, "valor");
        return of(new BigDecimal(value.trim()));
    }

    /** Cria a partir de um inteiro de centavos (evita erro de escala na fronteira). */
    public static Money ofCents(long cents) {
        return new Money(BigDecimal.valueOf(cents, SCALE));
    }

    public BigDecimal toBigDecimal() {
        return amount;
    }

    /** Representação canônica decimal (nunca notação científica), ex.: {@code "107.50"}. */
    public String toPlainString() {
        return amount.toPlainString();
    }

    public Money plus(Money other) {
        return of(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return of(amount.subtract(other.amount));
    }

    public Money negate() {
        return of(amount.negate());
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Money other && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return toPlainString();
    }
}
