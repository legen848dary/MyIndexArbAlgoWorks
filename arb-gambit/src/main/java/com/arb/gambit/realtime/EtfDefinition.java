package com.arb.gambit.realtime;

/**
 * Immutable ETF basket definition — one creation unit.
 *
 * <p>All arrays are defensively copied at construction time.
 * Instances are pre-allocated at startup and reused across NAV calculations — zero-GC on hot path.
 *
 * <p>Scaling convention:
 * <ul>
 *   <li>{@code sharesPerUnit} — raw share count, no scaling.</li>
 *   <li>{@code cashComponentPerUnit} — fixed-point 10^4 (same scale as constituent prices).</li>
 *   <li>{@code sharesOutstanding} — raw ETF unit count, no scaling.</li>
 * </ul>
 */
public final class EtfDefinition {

    public final String[] symbols;
    public final long[]   sharesPerUnit;
    public final long     cashComponentPerUnit;
    public final long     sharesOutstanding;

    public EtfDefinition(
        final String[] symbols,
        final long[]   sharesPerUnit,
        final long     cashComponentPerUnit,
        final long     sharesOutstanding)
    {
        if (symbols.length != sharesPerUnit.length) {
            throw new IllegalArgumentException("symbols and sharesPerUnit arrays must have equal length");
        }
        this.symbols              = symbols.clone();
        this.sharesPerUnit        = sharesPerUnit.clone();
        this.cashComponentPerUnit = cashComponentPerUnit;
        this.sharesOutstanding    = sharesOutstanding;
    }

    public int constituentCount() {
        return symbols.length;
    }
}
