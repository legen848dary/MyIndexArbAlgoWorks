package com.arb.gambit.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Warm/cold-path implied-volatility surface calibrator.
 *
 * <h3>Library choice</h3>
 * Uses {@code net.finmath:finmath-lib} ({@link net.finmath.functions.AnalyticFormulas}) for
 * analytic Black-Scholes option pricing. finmath-lib's B-S implementation uses all-primitive
 * {@code double} paths via Apache Commons Math3 {@code NormalDistribution.cumulativeProbability(double)}
 * which is unboxed — near-zero-GC for analytic calls (contrast: Strata's B-S boxes to
 * {@code Double} through {@code NORMAL.getCDF(Double x)} — 2 heap allocs per call).
 *
 * <h3>Responsibility</h3>
 * Calibrates an implied-volatility surface from market option prices.
 * Results are written as fixed-point BPS values to an {@link AtomicLong} shared with
 * any warm-path consumer via acquire-release ordering.
 *
 * <h3>Thread safety</h3>
 * Must NOT be called from the hot-path thread. Run periodically from a warm-path executor.
 */
public final class VolSurfaceCalibrator {

    // Stores implied vol in BPS × 100 (e.g. 20.00% vol → 2_000_000)
    private final AtomicLong impliedVolBps;

    public VolSurfaceCalibrator(final AtomicLong impliedVolBps) {
        this.impliedVolBps = impliedVolBps;
    }

    /**
     * Calibrate implied vol from a single at-the-money option and publish via {@code setRelease()}.
     *
     * <p>Uses {@link net.finmath.functions.AnalyticFormulas#blackScholesGeneralizedOptionValue}
     * which is all-primitive — no {@code Double} boxing.
     *
     * @param forward          forward price (not scaled — raw double for finmath-lib compatibility)
     * @param strike           option strike (raw double)
     * @param timeToMaturityYr time to maturity in years (raw double)
     * @param riskFreeRate     risk-free rate as fraction (e.g. 0.025 for 2.5%)
     * @param marketOptionPrice observed market option price (raw double)
     */
    public void calibrate(
        final double forward,
        final double strike,
        final double timeToMaturityYr,
        final double riskFreeRate,
        final double marketOptionPrice)
    {
        // Bisect to find implied vol — warm path, GC-tolerant
        double lo = 0.001, hi = 5.0;
        double vol = 0.2;

        for (int iter = 0; iter < 64; iter++) {
            final double mid = (lo + hi) * 0.5;
            final double modelPrice = net.finmath.functions.AnalyticFormulas
                .blackScholesGeneralizedOptionValue(forward, mid, timeToMaturityYr, riskFreeRate, strike);
            if (modelPrice < marketOptionPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
            vol = mid;
            if (hi - lo < 1e-7) break;
        }

        // Convert to BPS × 100 (e.g. 0.20 → 2_000_000) and publish via acquire-release
        final long volBps = (long) (vol * 10_000_000L);
        impliedVolBps.setRelease(volBps);
    }
}
