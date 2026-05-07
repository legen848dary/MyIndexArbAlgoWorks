package com.arb.gambit.realtime;

/**
 * Zero-GC futures fair value calculator using the cost-of-carry model.
 *
 * <h3>Formulae</h3>
 * <pre>
 *   FV = spotIndex + financingCost − dividendPv
 *
 *   financingCost = spotIndex × riskFreeRateBps × daysToExpiry
 *                  ─────────────────────────────────────────────
 *                              10_000 × 365
 *
 *   annualisedBasisBps = (futureMktPrice − fv) × 365 × 10_000 × 100
 *                        ────────────────────────────────────────────
 *                                  spotIndex × daysToExpiry
 * </pre>
 *
 * <h3>Scaling</h3>
 * <ul>
 *   <li>All price/index values: fixed-point 10^4 (e.g. HSI 19000 → 190_000_000).</li>
 *   <li>{@code riskFreeRateBps}: integer basis points (e.g. 2.50% = 250).</li>
 *   <li>{@code dividendPv}: fixed-point 10^4; pre-computed by warm-path {@code DividendCalendar}
 *       and passed via {@code AtomicLong.getAcquire()}.</li>
 *   <li>{@code annualisedBasisBps} return: fixed-point 10^2 (e.g. 150 BPS → 15_000).</li>
 * </ul>
 *
 * <h3>Overflow safety for {@code computeFv}</h3>
 * <ul>
 *   <li>spotIndex = 190_000_000 (HSI 19000), riskFreeRateBps = 500, daysToExpiry = 90.</li>
 *   <li>Numerator: 190_000_000 × 500 × 90 = 8.55 × 10^12 — within {@code long} range.</li>
 * </ul>
 *
 * <h3>Overflow safety for {@code annualisedBasisBps}</h3>
 * <ul>
 *   <li>Basis = 10_000_000 (1000 index points), constant = 365 × 10_000 × 100 = 3.65 × 10^8.</li>
 *   <li>Numerator: 10_000_000 × 3.65 × 10^8 = 3.65 × 10^15 — within {@code long} range.</li>
 * </ul>
 */
public final class FuturesFvCalculator {

    private static final long RATE_SCALE    = 10_000L;  // riskFreeRateBps denominator
    private static final long DAYS_PER_YEAR = 365L;
    private static final long BPS_SCALE     = 10_000L;  // convert fraction → BPS
    private static final long RESULT_SCALE  = 100L;     // output scale 10^2

    private FuturesFvCalculator() {}

    /**
     * Compute the fair futures value. Zero-GC — pure {@code long} arithmetic.
     *
     * @param spotIndex       spot index level, fixed-point 10^4
     * @param riskFreeRateBps risk-free funding rate in basis points (e.g. HIBOR 2.50% = 250)
     * @param daysToExpiry    calendar days until futures expiry
     * @param dividendPv      present value of dividends before expiry, fixed-point 10^4;
     *                        read from {@code AtomicLong.getAcquire()} on hot path
     * @return fair futures value, fixed-point 10^4
     */
    public static long computeFv(
        final long spotIndex,
        final int  riskFreeRateBps,
        final int  daysToExpiry,
        final long dividendPv)
    {
        final long financingCost =
            spotIndex * riskFreeRateBps * daysToExpiry / (RATE_SCALE * DAYS_PER_YEAR);
        return spotIndex + financingCost - dividendPv;
    }

    /**
     * Compute the annualised basis in BPS scaled by 10^2.
     * Example: 150.00 BPS annualised → returns 15_000.
     *
     * @param futureMktPrice current futures market price, fixed-point 10^4
     * @param fv             fair futures value from {@link #computeFv}, fixed-point 10^4
     * @param spotIndex      spot index level, fixed-point 10^4 (denominator normaliser)
     * @param daysToExpiry   calendar days until futures expiry
     * @return annualised basis BPS × 100 (fixed-point 10^2), or 0 if inputs are degenerate
     */
    public static long annualisedBasisBps(
        final long futureMktPrice,
        final long fv,
        final long spotIndex,
        final int  daysToExpiry)
    {
        if (daysToExpiry <= 0 || spotIndex <= 0) return 0L;
        // The 10^4 scale of (futureMktPrice - fv) and spotIndex cancel:
        // result = basis_fraction × (365/days) × BPS_SCALE × RESULT_SCALE
        return (futureMktPrice - fv) * DAYS_PER_YEAR * BPS_SCALE * RESULT_SCALE
               / (spotIndex * daysToExpiry);
    }
}
