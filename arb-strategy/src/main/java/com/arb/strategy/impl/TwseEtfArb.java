package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;
import com.arb.strategy.basket.TWSeConstituents;
import com.arb.strategy.basket.TWSeConstituents.Constituent;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B1: TWSE ETF NAV arbitrage — 2-leg delta-1 trade.
 *
 * <p><b>Leg 1:</b> SELL/BUY 0050.TW ETF when its market price diverges from NAV (from FvUpdate).
 * <p><b>Leg 2:</b> BUY/SELL a basket of representative TWSE 50 constituent stocks (TSMC-led).
 */
public final class TwseEtfArb implements Strategy {

    private static final String STRATEGY_NAME = "TwseEtfArb";
    private static final int    SYM_LEN       = 12;

    private final byte[]     symBuf;
    private final byte[]     etfSymBytes;
    private final String     etfSymbol;
    private final long       threshBps100;
    private final AtomicLong maxLots;

    private final byte[][] constituentSymBytes;

    private long etfMarketPrice = 0L;
    private long basketCounter  = 0L;

    public TwseEtfArb(final String etfSymbol,
                      final long threshBps100,
                      final AtomicLong maxLots) {
        this.etfSymbol    = etfSymbol;
        this.etfSymBytes  = Arrays.copyOf(etfSymbol.getBytes(StandardCharsets.US_ASCII), SYM_LEN);
        this.threshBps100 = threshBps100;
        this.maxLots      = maxLots;
        this.symBuf       = new byte[SYM_LEN];

        constituentSymBytes = new byte[TWSeConstituents.BASKET.length][SYM_LEN];
        for (int i = 0; i < TWSeConstituents.BASKET.length; i++) {
            final byte[] sym = TWSeConstituents.BASKET[i].symbol.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(sym, 0, constituentSymBytes[i], 0, Math.min(sym.length, SYM_LEN));
        }
    }

    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {
        tick.getSymbol(symBuf, 0);
        for (int i = 0; i < TWSeConstituents.BASKET.length; i++) {
            if (Arrays.equals(symBuf, constituentSymBytes[i])) {
                TWSeConstituents.BASKET[i].lastPrice10k = tick.price();
                return;
            }
        }
    }

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

        final long diff    = etfMarketPrice - nav;
        final long absDiff = Math.abs(diff);
        final long threshAbs = threshBps100 * nav / 1_000_000L;

        if (absDiff <= threshAbs) return;

        final boolean premium = diff > 0; // ETF trades above NAV → SELL ETF / BUY basket
        final Side etfSide   = premium ? Side.SELL : Side.BUY;
        final Side spotSide  = premium ? Side.BUY  : Side.SELL;
        final long lots      = maxLots.getAcquire();
        final long basketId  = ++basketCounter * 100L + 2L;

        final double bpsActual = (double) absDiff * 10_000.0 / nav;
        System.out.printf("[ARB SIGNAL] %s: ETF %s NAV by %.2f BPS (threshold=%.2f BPS)%n",
            STRATEGY_NAME, premium ? "PREMIUM above" : "DISCOUNT below",
            bpsActual, threshBps100 / 100.0);
        System.out.printf("[ARB TRADE]  basketId=%d: Leg1=%s %s @%d, Leg2=TWSE basket (%d stocks)%n",
            basketId, etfSide.name(), etfSymbol, etfMarketPrice, TWSeConstituents.BASKET.length);

        // Leg 1: ETF
        orders.sendLeg(basketId, 1, etfSymbol, etfSide, etfMarketPrice, lots, OrderType.LIMIT);

        // Leg 2: Constituent basket
        final long notional10k = lots * nav;
        for (final Constituent c : TWSeConstituents.BASKET) {
            final long legQty = TWSeConstituents.computeLotQty(c, notional10k);
            orders.sendLeg(basketId, 2, c.symbol, spotSide, c.lastPrice10k, legQty, OrderType.MARKET);
        }
        System.out.printf("[ARB BASKET] basketId=%d: ETF + %d constituent orders submitted.%n",
            basketId, TWSeConstituents.BASKET.length);
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}
}
