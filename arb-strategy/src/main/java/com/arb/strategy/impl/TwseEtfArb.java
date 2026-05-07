package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B1: TWSE ETF NAV arbitrage.
 * Caches the ETF's indicative equilibrium price from QuoteTick events; fires
 * when the market price deviates from the NAV (from FvUpdate) beyond the threshold.
 */
public final class TwseEtfArb implements Strategy {

    private static final int SYM_LEN = 12;

    private final byte[]     symBuf;
    private final byte[]     etfSymBytes;
    private final String     etfSymbol;
    private final long       threshBps100;
    private final AtomicLong maxLots;

    private long etfMarketPrice = 0L;

    public TwseEtfArb(final String etfSymbol,
                      final long threshBps100,
                      final AtomicLong maxLots) {
        this.etfSymbol   = etfSymbol;
        this.etfSymBytes = Arrays.copyOf(etfSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.threshBps100 = threshBps100;
        this.maxLots     = maxLots;
        this.symBuf      = new byte[SYM_LEN];
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {}

    @Override
    public void onQuote(final QuoteTickDecoder quote, final OrderSink orders) {
        quote.getSymbol(symBuf, 0);
        if (Arrays.equals(symBuf, etfSymBytes)) {
            etfMarketPrice = quote.iep();
        }
    }

    @Override
    public void onFvUpdate(final FvUpdateDecoder fv, final OrderSink orders) {
        fv.getSymbol(symBuf, 0);
        if (!Arrays.equals(symBuf, etfSymBytes)) return;
        if (etfMarketPrice == 0L) return;
        final long nav = fv.navPerUnit();
        if (nav == 0L) return;
        final long diff = Math.abs(etfMarketPrice - nav);
        if (diff > threshBps100 * nav / 1_000_000L) {
            final long lots = maxLots.getAcquire();
            orders.send(etfSymbol,
                    etfMarketPrice > nav ? Side.SELL : Side.BUY,
                    etfMarketPrice, lots, OrderType.LIMIT);
        }
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
