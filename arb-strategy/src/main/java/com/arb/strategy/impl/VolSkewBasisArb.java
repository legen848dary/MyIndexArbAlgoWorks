package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * E1: Volatility-skew basis arbitrage.
 * Adapts the entry threshold upward when implied vol exceeds realised vol,
 * reducing false signals in high-IV regimes.
 *
 * <p>adaptiveThresh = baseThreshBps100 + max(0, IV − RV) / ivRvScaleDown
 */
public final class VolSkewBasisArb implements Strategy {

    private static final int SYM_LEN = 12;

    private final byte[]     symBuf = new byte[SYM_LEN];
    private final long       baseThreshBps100;
    private final long       ivRvScaleDown;
    private final AtomicLong impliedVolBps;
    private final AtomicLong realisedVolBps;
    private final AtomicLong maxLots;

    public VolSkewBasisArb(final long baseThreshBps100,
                           final long ivRvScaleDown,
                           final AtomicLong impliedVolBps,
                           final AtomicLong realisedVolBps,
                           final AtomicLong maxLots) {
        this.baseThreshBps100 = baseThreshBps100;
        this.ivRvScaleDown    = ivRvScaleDown;
        this.impliedVolBps    = impliedVolBps;
        this.realisedVolBps   = realisedVolBps;
        this.maxLots          = maxLots;
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {}

    @Override
    public void onFvUpdate(final FvUpdateDecoder fv, final OrderSink orders) {
        final long iv                   = impliedVolBps.getAcquire();
        final long rv                   = realisedVolBps.getAcquire();
        final long adaptiveThreshBps100 = baseThreshBps100 + Math.max(0L, iv - rv) / ivRvScaleDown;
        final long bps                  = fv.annualisedBasisBps();
        if (bps <= adaptiveThreshBps100) return;
        final long lots = maxLots.getAcquire();
        fv.getSymbol(symBuf, 0);
        int slen = SYM_LEN;
        while (slen > 0 && symBuf[slen - 1] == 0) slen--;
        final String sym = new String(symBuf, 0, slen, StandardCharsets.US_ASCII);
        System.out.printf("[ARB SIGNAL] VolSkewBasisArb: basis=%.2f BPS > adaptiveThresh=%.2f BPS (IV=%.2f%%, RV=%.2f%%)%n",
            bps / 100.0, adaptiveThreshBps100 / 100.0, iv / 10000.0, rv / 10000.0);
        orders.send(sym, Side.SELL, fv.futuresFv(), lots, OrderType.LIMIT);
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
