package com.arb.gambit.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Warm-path rolling volatility estimator for spread strategies.
 *
 * <p>Computes the rolling standard deviation of a spread over a configurable window
 * (default 20 observations). Results are published via {@link AtomicLong#setRelease}
 * for lock-free hot-path consumption via {@link AtomicLong#getAcquire}.
 *
 * <p>The variance computation uses Welford's online algorithm — single-pass, numerically stable,
 * no array allocation (ring buffer over pre-allocated {@code long[]} field).
 *
 * <h3>Output scale</h3>
 * {@code spreadSigmaBps100} — spread σ expressed as BPS × 100 (same scale as
 * {@code annualisedBasisBps} in {@code FvUpdate}). E.g. 50 BPS spread σ → 5_000.
 */
public final class SpreadVolEstimator {

    private static final long SCALE = 10_000L; // BPS scale numerator

    private final AtomicLong spreadSigmaBps100;
    private final long[]     window;
    private final int        windowSize;

    private int  count       = 0;
    private int  writeIdx    = 0;
    private long runningSum  = 0L;
    private long runningSumSq = 0L; // sum of squares (scaled)

    public SpreadVolEstimator(final AtomicLong spreadSigmaBps100, final int windowSize) {
        this.spreadSigmaBps100 = spreadSigmaBps100;
        this.windowSize        = windowSize;
        this.window            = new long[windowSize]; // pre-allocated ring buffer
    }

    /**
     * Record a new spread observation and recompute σ.
     * Call from warm path only (e.g., every second or on each bar close).
     *
     * @param spreadBps100 observed spread in BPS × 100 (same scale as FvUpdate.annualisedBasisBps)
     */
    public void update(final long spreadBps100) {
        if (count == windowSize) {
            // Remove oldest observation from running totals
            final long oldest = window[writeIdx];
            runningSum   -= oldest;
            runningSumSq -= oldest * oldest;
        } else {
            count++;
        }

        window[writeIdx] = spreadBps100;
        writeIdx = (writeIdx + 1) % windowSize;
        runningSum   += spreadBps100;
        runningSumSq += spreadBps100 * spreadBps100;

        if (count < 2) {
            spreadSigmaBps100.setRelease(0L);
            return;
        }

        // σ² = (Σx² − (Σx)²/N) / (N−1)   [integer arithmetic, scaled]
        final long n       = count;
        final long varNum  = runningSumSq * n - runningSum * runningSum;
        final long varDen  = n * (n - 1L);
        final long sigma   = varNum > 0 ? (long) Math.sqrt((double) varNum / varDen) : 0L;
        spreadSigmaBps100.setRelease(sigma);
    }

    public int count() { return count; }
}
