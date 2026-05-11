package com.arb.strategy.basket;

import com.arb.sbe.Exchange;

/**
 * Representative HSI constituent basket for index arbitrage (simplified: 10 stocks, not all 50).
 * Used by {@link com.arb.strategy.impl.HkexBasisArb} for Leg 2 (delta-1 spot hedge).
 *
 * <p>Weights sum to ~680,000 out of 1,000,000 (representing ~68% of HSI weight).
 * Approximate base prices in HKD, scaled by 10^4.
 */
public final class HSIConstituents {

    public static final class Constituent {
        public final String symbol;
        public final Exchange exchange;
        public final long weightMicro;      // weight ÷ 1,000,000 = fraction
        public final long basePrice10k;     // base price × 10^4
        public volatile long lastPrice10k;  // updated on onMarketData

        public Constituent(final String symbol, final Exchange exchange,
                           final long weightMicro, final long basePrice10k) {
            this.symbol         = symbol;
            this.exchange       = exchange;
            this.weightMicro    = weightMicro;
            this.basePrice10k   = basePrice10k;
            this.lastPrice10k   = basePrice10k;
        }
    }

    /**
     * Representative basket — weights scaled so they sum to exactly 1,000,000.
     * Full 100% coverage ensures Leg 2 spot notional balances Leg 1 futures notional,
     * so P&L reflects only the basis spread captured, not a notional coverage gap.
     */
    public static final Constituent[] BASKET = {
        new Constituent("0700.HK", Exchange.HKEX, 146_000L, 3_500_000L),  // Tencent ~350 HKD, 14.6%
        new Constituent("0005.HK", Exchange.HKEX, 130_000L,   620_000L),  // HSBC ~62 HKD, 13%
        new Constituent("0941.HK", Exchange.HKEX, 116_000L,   580_000L),  // China Mobile ~58 HKD, 11.6%
        new Constituent("0388.HK", Exchange.HKEX, 109_000L, 2_200_000L),  // HKEx ~220 HKD, 10.9%
        new Constituent("1299.HK", Exchange.HKEX, 101_000L,   630_000L),  // AIA ~63 HKD, 10.1%
        new Constituent("2318.HK", Exchange.HKEX,  94_000L,   410_000L),  // Ping An ~41 HKD, 9.4%
        new Constituent("0939.HK", Exchange.HKEX,  87_000L,    55_000L),  // CCB ~5.5 HKD, 8.7%
        new Constituent("1398.HK", Exchange.HKEX,  80_000L,    42_000L),  // ICBC ~4.2 HKD, 8%
        new Constituent("0883.HK", Exchange.HKEX,  72_000L,   125_000L),  // CNOOC ~12.5 HKD, 7.2%
        new Constituent("1113.HK", Exchange.HKEX,  65_000L,   450_000L),  // CKA ~45 HKD, 6.5%
    };

    /**
     * Compute number of shares to buy for a given futures notional.
     * lotQty = max(1, notionalScaled4 * weightMicro / lastPrice10k / 1_000_000)
     */
    public static long computeLotQty(final Constituent c, final long notionalScaled4) {
        if (c.lastPrice10k <= 0L) return 1L;
        return Math.max(1L, notionalScaled4 * c.weightMicro / c.lastPrice10k / 1_000_000L);
    }

    private HSIConstituents() {}
}
