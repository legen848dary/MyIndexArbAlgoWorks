package com.arb.strategy.basket;

import com.arb.sbe.Exchange;

/**
 * Representative TWSE 50 constituent basket (simplified: primary stocks).
 * Used by {@link com.arb.strategy.impl.TwseEtfArb} for Leg 2 spot hedge.
 * TSMC (2330.TW) dominates Taiwan index at ~35% weight.
 */
public final class TWSeConstituents {

    public static final class Constituent {
        public final String symbol;
        public final Exchange exchange;
        public final long weightMicro;
        public final long basePrice10k;
        public volatile long lastPrice10k;

        public Constituent(final String symbol, final Exchange exchange,
                           final long weightMicro, final long basePrice10k) {
            this.symbol       = symbol;
            this.exchange     = exchange;
            this.weightMicro  = weightMicro;
            this.basePrice10k = basePrice10k;
            this.lastPrice10k = basePrice10k;
        }
    }

    /**
     * Representative basket — weights scaled so they sum to exactly 1,000,000.
     * Full 100% coverage ensures Leg 2 basket notional balances Leg 1 ETF notional,
     * so P&L reflects only the NAV spread captured, not a notional coverage gap.
     */
    public static final Constituent[] BASKET = {
        new Constituent("2330.TW", Exchange.TAIFEX, 583_000L, 9_500_000L),  // TSMC ~950 TWD, 58.3%
        new Constituent("2317.TW", Exchange.TAIFEX, 134_000L, 1_600_000L),  // Hon Hai ~160 TWD, 13.4%
        new Constituent("2454.TW", Exchange.TAIFEX, 108_000L, 3_500_000L),  // MediaTek ~350 TWD, 10.8%
        new Constituent("2412.TW", Exchange.TAIFEX,  92_000L, 1_200_000L),  // Chunghwa Telecom ~120 TWD, 9.2%
        new Constituent("2308.TW", Exchange.TAIFEX,  83_000L,   900_000L),  // Delta Electronics ~90 TWD, 8.3%
    };

    public static long computeLotQty(final Constituent c, final long notionalScaled4) {
        if (c.lastPrice10k <= 0L) return 1L;
        return Math.max(1L, notionalScaled4 * c.weightMicro / c.lastPrice10k / 1_000_000L);
    }

    private TWSeConstituents() {}
}
