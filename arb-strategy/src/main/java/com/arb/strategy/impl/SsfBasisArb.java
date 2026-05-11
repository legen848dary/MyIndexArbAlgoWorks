package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * C1: Single-Stock Futures (SSF) basis arbitrage.
 * Compares the SSF price to the spot price plus a carry estimate; fires SELL
 * when futures trade rich beyond the carry + buffer.
 *
 * <p>Carry BPS = (2.5% × daysToExpiry / 365) expressed as BPS × 100,
 * using rateScaled4 = 250 (i.e., 2.5% in 10^4 fixed-point).
 */
public final class SsfBasisArb implements Strategy {

    private static final int  SYM_LEN      = 12;
    private static final long RATE_SCALED4 = 250L; // 2.5% in 10^4 fixed-point

    private final byte[]     symBuf;
    private final byte[]     ssfSymBytes;
    private final byte[]     spotSymBytes;
    private final String     ssfSymbol;
    private final long       carryBps100;
    private final long       bufferBps100;
    private final AtomicLong dividendPv;
    private final AtomicLong maxLots;

    private long ssfPrice  = 0L;
    private long spotPrice = 0L;

    public SsfBasisArb(final String ssfSymbol,
                       final String spotSymbol,
                       final int contractMult,
                       final int daysToExpiry,
                       final AtomicLong dividendPv,
                       final long bufferBps100,
                       final AtomicLong maxLots) {
        this.ssfSymbol   = ssfSymbol;
        this.ssfSymBytes  = Arrays.copyOf(ssfSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.spotSymBytes = Arrays.copyOf(spotSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.dividendPv   = dividendPv;
        this.bufferBps100 = bufferBps100;
        this.maxLots      = maxLots;
        this.carryBps100  = RATE_SCALED4 * daysToExpiry / 365L;
        this.symBuf       = new byte[SYM_LEN];
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {
        tick.getSymbol(symBuf, 0);
        if (Arrays.equals(symBuf, ssfSymBytes)) {
            ssfPrice = tick.price();
        } else if (Arrays.equals(symBuf, spotSymBytes)) {
            spotPrice = tick.price();
        }
        if (ssfPrice == 0L || spotPrice == 0L) return;
        final long spread = (ssfPrice - spotPrice) * 10_000L * 100L / spotPrice;
        if (spread > carryBps100 + bufferBps100) {
            final long lots = maxLots.getAcquire();
            System.out.printf("[ARB SIGNAL] SsfBasisArb: SSF spread=%.2f BPS > carry(%.2f)+buffer(%.2f) → SELL SSF%n",
                spread / 100.0, carryBps100 / 100.0, bufferBps100 / 100.0);
            orders.send(ssfSymbol, Side.SELL, ssfPrice, lots, OrderType.LIMIT);
        }
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
