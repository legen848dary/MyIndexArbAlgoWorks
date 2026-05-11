package com.arb.execution;

/**
 * Pre-trade risk configuration parameters.
 * All price limits are fixed-point 10^4 (matching SBE price scale).
 * All quantity limits are in lots.
 */
public final class RiskConfig {

    /** Maximum quantity per single order in lots. Fat-finger guard. */
    public final long maxQtyPerOrderLots;

    /**
     * Maximum allowed price deviation from last known price, expressed as BPS × 100.
     * E.g. 100_000 = 1000.00 BPS = 10%. Order rejected if price deviates beyond this.
     * 0 = skip price fat-finger check (use when lastKnownPrice is not yet populated).
     */
    public final long maxPriceDeviationBps100;

    /** Maximum absolute net position per symbol in lots. Position-limit guard. */
    public final long maxNetPositionLots;

    public RiskConfig(final long maxQtyPerOrderLots,
                      final long maxPriceDeviationBps100,
                      final long maxNetPositionLots) {
        this.maxQtyPerOrderLots       = maxQtyPerOrderLots;
        this.maxPriceDeviationBps100  = maxPriceDeviationBps100;
        this.maxNetPositionLots       = maxNetPositionLots;
    }

    /**
     * Default prototype config: permissive limits for demo trading.
     * Constituent basket orders can exceed 2000 lots (e.g. CCB, ICBC at 10-lot HSI futures notional),
     * so maxQtyPerOrderLots is set high enough not to false-reject hedges.
     * maxNetPositionLots is large enough to avoid accumulation rejections across multiple arb cycles.
     */
    public static RiskConfig defaultConfig() {
        return new RiskConfig(10_000L, 100_000L, 1_000_000L);
    }
}
