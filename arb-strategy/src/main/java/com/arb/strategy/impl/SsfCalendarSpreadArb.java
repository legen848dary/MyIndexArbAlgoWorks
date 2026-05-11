package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * C2: SSF calendar-spread arbitrage.
 * Caches near-month and far-month SSF prices; fires when the observed spread
 * deviates from the theoretical carry spread by more than sigmaMultiplier × σ.
 *
 * <p>theoreticalSpread = nearPrice × (farDays − nearDays) × rateScaled4 / (365 × 10000)
 */
public final class SsfCalendarSpreadArb implements Strategy {

    private static final int  SYM_LEN      = 12;
    private static final long RATE_SCALED4 = 250L; // 2.5%

    private final byte[]     symBuf;
    private final byte[]     nearSymBytes;
    private final byte[]     farSymBytes;
    private final String     nearSymbol;
    private final long       theoreticalSpreadFactor; // (farDays-nearDays) * RATE_SCALED4 / 365
    private final AtomicLong spreadSigmaBps100;
    private final long       sigmaMultiplier;

    private long nearPrice = 0L;
    private long farPrice  = 0L;

    public SsfCalendarSpreadArb(final String nearSymbol,
                                final String farSymbol,
                                final int contractMult,
                                final int nearDays,
                                final int farDays,
                                final AtomicLong spreadSigmaBps100,
                                final long sigmaMultiplier) {
        this.nearSymbol            = nearSymbol;
        this.nearSymBytes          = Arrays.copyOf(nearSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.farSymBytes           = Arrays.copyOf(farSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.spreadSigmaBps100     = spreadSigmaBps100;
        this.sigmaMultiplier       = sigmaMultiplier;
        this.theoreticalSpreadFactor = (long)(farDays - nearDays) * RATE_SCALED4 / 365L;
        this.symBuf                = new byte[SYM_LEN];
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {
        tick.getSymbol(symBuf, 0);
        if (Arrays.equals(symBuf, nearSymBytes)) {
            nearPrice = tick.price();
        } else if (Arrays.equals(symBuf, farSymBytes)) {
            farPrice = tick.price();
        }
        if (nearPrice == 0L || farPrice == 0L) return;
        final long theoreticalSpread = nearPrice * theoreticalSpreadFactor / 10_000L;
        final long observedSpread    = farPrice - nearPrice;
        final long sigma             = spreadSigmaBps100.getAcquire();
        if (Math.abs(observedSpread - theoreticalSpread) > sigmaMultiplier * sigma) {
            final Side side = observedSpread > theoreticalSpread ? Side.SELL : Side.BUY;
            System.out.printf("[ARB SIGNAL] SsfCalendarSpreadArb: obs=%.4f theo=%.4f delta=%.4f > %d×sigma → %s%n",
                observedSpread / 10000.0, theoreticalSpread / 10000.0,
                Math.abs(observedSpread - theoreticalSpread) / 10000.0, sigmaMultiplier, side.name());
            orders.send(nearSymbol, side, nearPrice, 1L, OrderType.LIMIT);
        }
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
