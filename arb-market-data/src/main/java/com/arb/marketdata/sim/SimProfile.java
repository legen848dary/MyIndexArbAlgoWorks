package com.arb.marketdata.sim;

/**
 * Simulation profiles for the arb demo system.
 * Each profile simulates a specific type of index arbitrage opportunity.
 */
public enum SimProfile {
    /** HSI futures basis arbitrage: futures trade premium/discount vs fair value */
    HKEX_BASIS_ARB,
    /** 0050.TW ETF NAV arbitrage: ETF market price diverges from NAV */
    TWSE_ETF_ARB,
    /** TSMC SSF calendar spread: near/far contract spread widens */
    SSF_CALENDAR,
    /** HSI/CSI300 cross-market pair: z-score divergence triggers long/short pair */
    HK_CN_PAIR
}
