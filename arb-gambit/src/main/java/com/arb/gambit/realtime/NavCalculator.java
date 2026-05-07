package com.arb.gambit.realtime;

/**
 * Zero-GC ETF NAV calculator.
 *
 * <h3>Formula</h3>
 * <pre>
 *   NAV per ETF unit = ( Σ(sharesPerUnit[i] × price[i]) + cashComponentPerUnit )
 *                      / sharesOutstanding
 * </pre>
 *
 * <h3>Scaling</h3>
 * <ul>
 *   <li>All price values are fixed-point at scale 10^4 (e.g. HK$380.50 → 3_805_000).</li>
 *   <li>{@code sharesPerUnit[i]} and {@code sharesOutstanding} are raw counts (no scale).</li>
 *   <li>{@code cashComponentPerUnit} is fixed-point 10^4.</li>
 *   <li>The numerator sum has scale 10^4 (sharesPerUnit × price(10^4) + cash(10^4)).</li>
 *   <li>Dividing by {@code sharesOutstanding} (raw) yields result at scale 10^4.</li>
 * </ul>
 *
 * <h3>Overflow safety</h3>
 * <ul>
 *   <li>Worst case: sharesPerUnit = 100_000, price = 100_000_000 (HK$10,000 at 10^4 scale).</li>
 *   <li>Product per constituent: 100_000 × 100_000_000 = 10^13.</li>
 *   <li>Sum over 50 constituents: 50 × 10^13 = 5 × 10^14 — safely within {@code long} range.</li>
 * </ul>
 */
public final class NavCalculator {

    private NavCalculator() {}

    /**
     * Compute the NAV per ETF creation unit. Zero-GC — no heap allocation.
     *
     * @param def    the ETF basket definition (pre-allocated, immutable)
     * @param prices constituent prices indexed by position in {@code def.symbols},
     *               each fixed-point at scale 10^4; caller must pre-allocate this array
     * @return NAV per ETF unit, fixed-point scale 10^4
     */
    public static long computeNav(final EtfDefinition def, final long[] prices) {
        long sum = def.cashComponentPerUnit;
        for (int i = 0; i < def.sharesPerUnit.length; i++) {
            sum += def.sharesPerUnit[i] * prices[i];
        }
        return sum / def.sharesOutstanding;
    }
}
