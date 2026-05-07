package com.arb.marketdata.replay;

/**
 * Parsed frame from a JSONL scenario file.
 * Either a MarketDataTick frame ("MD") or an FvUpdate frame ("FV").
 */
public record ScenarioFrame(
    String type,                   // "MD" or "FV"
    long   tsMillis,               // relative timestamp in milliseconds from start
    String symbol,
    String exchange,
    long   price,                  // MD only — fixed-point ×10^4
    long   qty,                    // MD only
    long   futuresFv,              // FV only — fixed-point ×10^4
    long   navPerUnit,             // FV only
    long   basis,                  // FV only
    long   annualisedBasisBps100   // FV only
) {
    /** Convenience factory for MD frames. */
    public static ScenarioFrame md(long ts, String sym, String ex, long price, long qty) {
        return new ScenarioFrame("MD", ts, sym, ex, price, qty, 0, 0, 0, 0);
    }

    /** Convenience factory for FV frames. */
    public static ScenarioFrame fv(long ts, String sym, String ex, long fv, long nav, long basis, long bps100) {
        return new ScenarioFrame("FV", ts, sym, ex, 0, 0, fv, nav, basis, bps100);
    }
}
