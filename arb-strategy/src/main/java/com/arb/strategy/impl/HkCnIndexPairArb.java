package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D1: HK/CN index pair statistical-arbitrage.
 * Computes a z-score of the hedge-ratio-adjusted spread; fires when |z| exceeds
 * the entry threshold and suppresses when |z| falls below the exit threshold.
 *
 * <p>spread = hkPrice − beta × cnPrice / 10_000
 * <p>z × 100 = (spread − mean) × 100 / sigma
 */
public final class HkCnIndexPairArb implements Strategy {

    private static final int SYM_LEN = 12;

    private final byte[]     symBuf;
    private final byte[]     hkSymBytes;
    private final byte[]     cnSymBytes;
    private final String     hkSymbol;
    private final String     cnSymbol;
    private final AtomicLong betaScaled4;
    private final AtomicLong spreadMeanScaled4;
    private final AtomicLong spreadSigmaScaled4;
    private final long       entryZScore100;
    private final long       exitZScore100;

    private long hkPrice = 0L;
    private long cnPrice = 0L;
    private long basketCounter = 0L;

    public HkCnIndexPairArb(final String hkSymbol,
                             final String cnSymbol,
                             final AtomicLong betaScaled4,
                             final AtomicLong spreadMeanScaled4,
                             final AtomicLong spreadSigmaScaled4,
                             final long entryZScore100,
                             final long exitZScore100) {
        this.hkSymbol           = hkSymbol;
        this.cnSymbol           = cnSymbol;
        this.hkSymBytes         = Arrays.copyOf(hkSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.cnSymBytes         = Arrays.copyOf(cnSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.betaScaled4        = betaScaled4;
        this.spreadMeanScaled4  = spreadMeanScaled4;
        this.spreadSigmaScaled4 = spreadSigmaScaled4;
        this.entryZScore100     = entryZScore100;
        this.exitZScore100      = exitZScore100;
        this.symBuf             = new byte[SYM_LEN];
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {
        tick.getSymbol(symBuf, 0);
        if (Arrays.equals(symBuf, hkSymBytes)) {
            hkPrice = tick.price();
        } else if (Arrays.equals(symBuf, cnSymBytes)) {
            cnPrice = tick.price();
        }
        if (hkPrice == 0L || cnPrice == 0L) return;
        final long beta      = betaScaled4.getAcquire();
        final long spread    = hkPrice - beta * cnPrice / 10_000L;
        final long mean      = spreadMeanScaled4.getAcquire();
        final long sigma     = spreadSigmaScaled4.getAcquire();
        final long zScore100 = sigma > 0L ? (spread - mean) * 100L / sigma : 0L;
        if (Math.abs(zScore100) < exitZScore100) return;
        if (Math.abs(zScore100) > entryZScore100) {
            final Side hkSide = zScore100 > 0L ? Side.SELL : Side.BUY;
            final Side cnSide = zScore100 > 0L ? Side.BUY  : Side.SELL;
            final long basketId = ++basketCounter;
            System.out.printf("[ARB SIGNAL] HkCnIndexPairArb: z=%.2f (entry=%.2f) basketId=%d → %s %s / %s %s%n",
                zScore100 / 100.0, entryZScore100 / 100.0, basketId,
                hkSide.name(), hkSymbol, cnSide.name(), cnSymbol);
            // BUY the underperformer, SELL the outperformer
            orders.sendLeg(basketId, 1, hkSymbol, hkSide, hkPrice, 1L, OrderType.LIMIT);
            orders.sendLeg(basketId, 2, cnSymbol, cnSide, cnPrice, 1L, OrderType.LIMIT);
        }
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
