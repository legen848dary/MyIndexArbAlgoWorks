package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A2: MHI/HSI intra-product basis arb.
 * Caches HSI and MHI last prices; fires when the implied spread (HSI - 5×MHI)
 * exceeds the percentage threshold.
 */
public final class MhiHsiBasisArb implements Strategy {

    private static final int     SYM_LEN = 12;
    private static final byte[]  HSI_SYM =
            Arrays.copyOf("HSI.HK".getBytes(StandardCharsets.US_ASCII), SYM_LEN);
    private static final byte[]  MHI_SYM =
            Arrays.copyOf("MHI.HK".getBytes(StandardCharsets.US_ASCII), SYM_LEN);

    private final byte[] symBuf = new byte[SYM_LEN];
    private final long   threshBps100;

    private long hsiPriceScaled4 = 0L;
    private long mhiPriceScaled4 = 0L;

    public MhiHsiBasisArb(final long threshBps100) {
        this.threshBps100 = threshBps100;
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {
        tick.getSymbol(symBuf, 0);
        if (Arrays.equals(symBuf, HSI_SYM)) {
            hsiPriceScaled4 = tick.price();
        } else if (Arrays.equals(symBuf, MHI_SYM)) {
            mhiPriceScaled4 = tick.price();
        }
        if (hsiPriceScaled4 == 0L || mhiPriceScaled4 == 0L) return;
        final long spread       = hsiPriceScaled4 - 5L * mhiPriceScaled4;
        final long spreadBps100 = Math.abs(spread) * 10_000L * 100L / hsiPriceScaled4;
        if (spreadBps100 > threshBps100) {
            final Side side = spread > 0 ? Side.BUY : Side.SELL;
            System.out.printf("[ARB SIGNAL] MhiHsiBasisArb: spread=%.4f (threshold=%.4f) → %s%n",
                spread / 10000.0, threshBps100 / 10000.0, side.name());
            orders.send("MHI.HK", side, mhiPriceScaled4, 1L, OrderType.LIMIT);
        }
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
