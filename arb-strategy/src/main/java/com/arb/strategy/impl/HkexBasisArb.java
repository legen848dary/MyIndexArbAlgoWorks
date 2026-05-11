package com.arb.strategy.impl;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;
import com.arb.strategy.basket.HSIConstituents;
import com.arb.strategy.basket.HSIConstituents.Constituent;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A1: HKEX Futures basis-arbitrage strategy — 2-leg delta-1 index arbitrage.
 *
 * <p><b>Leg 1 (Signal leg):</b> SELL/BUY HSI futures at the fair value price
 * when basis exceeds the threshold (positive basis = futures rich vs FV → SELL).
 *
 * <p><b>Leg 2 (Hedge leg):</b> BUY/SELL a basket of 10 representative HSI constituent stocks
 * weighted proportionally to their index weights, creating a delta-1 position that offsets
 * the futures leg and captures the basis premium as risk-free P&L once the spread converges.
 */
public final class HkexBasisArb implements Strategy {

    private static final String STRATEGY_NAME = "HkexBasisArb";
    private static final int    SYM_LEN       = 12;

    private final byte[]     symBuf = new byte[SYM_LEN];
    private final long       entryThreshBps100;
    private final long       exitThreshBps100;
    private final long       lotSize;
    private final AtomicLong maxLots;

    // Pre-allocated symbol byte arrays for constituent matching (zero-GC)
    private final byte[][] constituentSymBytes;

    // Local basket ID counter (monotonic, strategy-scoped)
    private long basketCounter = 0L;

    // 25-second cooldown — one basket per arb window
    private long lastSignalNs = 0L;
    private static final long COOLDOWN_NS = 25_000_000_000L;

    private final com.arb.common.metrics.LatencyRecorder signalLatency = new com.arb.common.metrics.LatencyRecorder();

    public HkexBasisArb(final long entryThreshBps100,
                        final long exitThreshBps100,
                        final long lotSize,
                        final AtomicLong maxLots) {
        this.entryThreshBps100 = entryThreshBps100;
        this.exitThreshBps100  = exitThreshBps100;
        this.lotSize           = lotSize;
        this.maxLots           = maxLots;

        // Pre-allocate constituent symbol byte arrays for zero-GC comparison
        constituentSymBytes = new byte[HSIConstituents.BASKET.length][SYM_LEN];
        for (int i = 0; i < HSIConstituents.BASKET.length; i++) {
            final byte[] sym = HSIConstituents.BASKET[i].symbol.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(sym, 0, constituentSymBytes[i], 0, Math.min(sym.length, SYM_LEN));
        }
    }

    public com.arb.common.metrics.LatencyRecorder signalLatencyRecorder() { return signalLatency; }

    /**
     * Track constituent prices for Leg 2 lot-size calculation.
     */
    @Override
    public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {
        tick.getSymbol(symBuf, 0);
        for (int i = 0; i < HSIConstituents.BASKET.length; i++) {
            if (bytesMatch(symBuf, constituentSymBytes[i])) {
                HSIConstituents.BASKET[i].lastPrice10k = tick.price();
                return;
            }
        }
    }

    /**
     * Main signal: fire 2-leg basket when basis exceeds threshold.
     */
    @Override
    public void onFvUpdate(final FvUpdateDecoder fv, final OrderSink orders) {
        final long t0 = System.nanoTime();
        final long bps = fv.annualisedBasisBps();

        fv.getSymbol(symBuf, 0);
        if (symBuf[0] != 'H') return; // quick filter: only process HSI.HK (starts with H)

        final String sym = decodeSym();
        if (!sym.startsWith("HSI")) return;

        if (bps <= exitThreshBps100) {
            return; // basis below threshold — no signal, don't consume the cooldown
        }

        final long nowNs = System.nanoTime();
        if (nowNs - lastSignalNs < COOLDOWN_NS) return; // still in cooldown
        lastSignalNs = nowNs;

        final long lots = Math.min(lotSize, maxLots.getAcquire());
        final Side futureSide = bps > 0 ? Side.SELL : Side.BUY;
        final Side spotSide   = bps > 0 ? Side.BUY  : Side.SELL;

        final long basketId = ++basketCounter * 100L + 1L; // unique: counter×100 + strategyId

        System.out.printf("[ARB SIGNAL] %s: basis=%.2f BPS (threshold=%.2f BPS) → %s %d HSI futures lots%n",
            STRATEGY_NAME, bps / 100.0, exitThreshBps100 / 100.0, futureSide.name(), lots);
        System.out.printf("[ARB TRADE]  basketId=%d: Leg1=%s %s.HK futures @FV=%d, Leg2=constituent basket (%d stocks)%n",
            basketId, futureSide.name(), sym, fv.futuresFv(), HSIConstituents.BASKET.length);

        // ── Leg 1: HSI Futures (signal leg) ──────────────────────────────────
        orders.sendLeg(basketId, 1, sym, futureSide, fv.futuresFv(), lots, OrderType.LIMIT);

        // ── Leg 2: HSI Constituent Basket (delta-1 hedge leg) ────────────────
        final long notional10k = lots * fv.futuresFv();
        int legTwoCount = 0;
        for (final Constituent c : HSIConstituents.BASKET) {
            final long legQty = HSIConstituents.computeLotQty(c, notional10k);
            orders.sendLeg(basketId, 2, c.symbol, spotSide, c.lastPrice10k, legQty, OrderType.MARKET);
            legTwoCount++;
        }

        System.out.printf("[ARB BASKET] basketId=%d: %d constituent orders submitted. Awaiting fills...%n",
            basketId, legTwoCount);
        signalLatency.record(System.nanoTime() - t0);
    }

    @Override
    public void onTimer(final long nowNanos, final OrderSink orders) {}

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean bytesMatch(final byte[] a, final byte[] b) {
        for (int i = 0; i < SYM_LEN; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private String decodeSym() {
        int slen = SYM_LEN;
        while (slen > 0 && (symBuf[slen - 1] == 0 || symBuf[slen - 1] == ' ')) slen--;
        return new String(symBuf, 0, slen, StandardCharsets.US_ASCII);
    }
}
