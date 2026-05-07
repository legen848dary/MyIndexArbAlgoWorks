package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B2: Cross-border ETF arbitrage (HKD NAV vs. CNH futures).
 * Converts the HKD NAV to CNH using the live FX bridge and fires when the
 * CNH-adjusted basis exceeds the threshold.
 */
public final class CrossBorderEtfArb implements Strategy {

    private static final int SYM_LEN = 12;

    private final byte[]     symBuf;
    private final byte[]     etfSymBytes;
    private final String     etfSymbol;
    private final long       threshBps100;
    private final AtomicLong fxRateHkdCnh100;

    public CrossBorderEtfArb(final String etfSymbol,
                              final long threshBps100,
                              final AtomicLong fxRateHkdCnh100) {
        this.etfSymbol       = etfSymbol;
        this.etfSymBytes     = Arrays.copyOf(etfSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.threshBps100    = threshBps100;
        this.fxRateHkdCnh100 = fxRateHkdCnh100;
        this.symBuf          = new byte[SYM_LEN];
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {}

    @Override
    public void onFvUpdate(final FvUpdateDecoder fv, final OrderSink orders) {
        fv.getSymbol(symBuf, 0);
        if (!Arrays.equals(symBuf, etfSymBytes)) return;
        final long fxRate = fxRateHkdCnh100.getAcquire();
        final long nav    = fv.navPerUnit();
        if (nav == 0L || fxRate == 0L) return;
        final long navCnh  = nav * fxRate / 100_000L;
        if (navCnh == 0L) return;
        final long cnhBasis = (fv.futuresFv() - navCnh) * 1_000_000L / navCnh;
        if (cnhBasis > threshBps100) {
            orders.send(etfSymbol, Side.SELL, fv.futuresFv(), 1L, OrderType.LIMIT);
        }
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
