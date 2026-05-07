package com.arb.marketdata.normalizer;

/**
 * Converts floating-point exchange prices to fixed-point longs for the hot path.
 * Scale factor: 10^4 (four decimal places of precision).
 *
 * e.g.  380.50 HKD  ->  3_805_000L
 *       45.125 TWD  ->    451_250L
 */
public final class PriceNormalizer {

    public static final long SCALE = 10_000L;

    private PriceNormalizer() {}

    /** Convert a raw double price to a fixed-point long. */
    public static long normalize(final double price) {
        return Math.round(price * SCALE);
    }

    /** Convert a fixed-point long back to a double (for display/logging only — not hot path). */
    public static double denormalize(final long normalizedPrice) {
        return (double) normalizedPrice / SCALE;
    }
}
