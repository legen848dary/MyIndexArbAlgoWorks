package com.arb.strategy.calculator;

/**
 * Stateless basis calculator.
 *
 * <p><b>Basis</b> = FuturePrice − IndexFairValue − CostOfCarry
 *
 * <ul>
 *   <li>Positive basis → futures trading at a premium → sell future, buy basket.</li>
 *   <li>Negative basis → futures trading at a discount → buy future, sell basket.</li>
 * </ul>
 *
 * <p>All values are fixed-point longs (scale 10^4, matching {@code PriceNormalizer.SCALE}).
 * Zero heap allocation — all arithmetic is on primitives.
 */
public final class BasisCalculator {

    /** Annualised cost-of-carry in fixed-point units (scale 10^4). */
    private final long costOfCarry;

    /**
     * @param costOfCarry annualised financing cost in fixed-point units (scale 10^4).
     *                    E.g. for a 2% carry on an index at 20,000: 20_000 * 10_000 * 0.02 = 4_000_000L.
     *                    Pass 0 for a simplified carry-free model.
     */
    public BasisCalculator(final long costOfCarry) {
        this.costOfCarry = costOfCarry;
    }

    /**
     * Compute basis. Zero-allocation hot path.
     *
     * @param futurePrice futures market price (fixed-point, scale 10^4)
     * @param indexValue  theoretical fair value from {@link IndexCalculator#computeIndex()} (fixed-point)
     * @return basis in fixed-point units (scale 10^4)
     */
    public long compute(final long futurePrice, final long indexValue) {
        return futurePrice - indexValue - costOfCarry;
    }

    public long costOfCarry() {
        return costOfCarry;
    }
}
