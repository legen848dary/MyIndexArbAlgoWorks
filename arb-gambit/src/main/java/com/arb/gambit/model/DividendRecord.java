package com.arb.gambit.model;

/**
 * Immutable dividend record for a single constituent.
 *
 * <p>Used by {@link DividendCalendar} to compute dividend PV.
 *
 * <h3>Scaling</h3>
 * <ul>
 *   <li>{@code grossAmountPerShareScaled4} — gross dividend per share, fixed-point 10^4.</li>
 *   <li>{@code exDateEpochDays} — Java epoch day ({@code LocalDate.toEpochDay()}).</li>
 * </ul>
 */
public final class DividendRecord {

    public final String symbol;
    public final long   exDateEpochDays;
    public final long   grossAmountPerShareScaled4;

    public DividendRecord(
        final String symbol,
        final long   exDateEpochDays,
        final long   grossAmountPerShareScaled4)
    {
        this.symbol                    = symbol;
        this.exDateEpochDays           = exDateEpochDays;
        this.grossAmountPerShareScaled4 = grossAmountPerShareScaled4;
    }
}
