package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A1: HKEX Futures basis-arbitrage strategy.
 * Fires a SELL (positive basis) or BUY (negative basis) when the annualised basis
 * exceeds the exit suppression threshold, sourced from FvUpdateDecoder.
 */
public final class HkexBasisArb implements Strategy {

    private static final int SYM_LEN = 12;

    private final byte[]     symBuf = new byte[SYM_LEN];
    private final long       entryThreshBps100;
    private final long       exitThreshBps100;
    private final long       lotSize;
    private final AtomicLong maxLots;

    public HkexBasisArb(final long entryThreshBps100,
                        final long exitThreshBps100,
                        final long lotSize,
                        final AtomicLong maxLots) {
        this.entryThreshBps100 = entryThreshBps100;
        this.exitThreshBps100  = exitThreshBps100;
        this.lotSize           = lotSize;
        this.maxLots           = maxLots;
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {}

    @Override
    public void onFvUpdate(final FvUpdateDecoder fv, final OrderSink orders) {
        final long bps = fv.annualisedBasisBps();
        if (bps <= exitThreshBps100) return;
        final long lots = Math.min(lotSize, maxLots.getAcquire());
        fv.getSymbol(symBuf, 0);
        int slen = SYM_LEN;
        while (slen > 0 && symBuf[slen - 1] == 0) slen--;
        final String sym = new String(symBuf, 0, slen, StandardCharsets.US_ASCII);
        orders.send(sym, bps > 0 ? Side.SELL : Side.BUY, fv.futuresFv(), lots, OrderType.LIMIT);
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
