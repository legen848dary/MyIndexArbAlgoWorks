package com.arb.strategy;

import com.arb.sbe.*;
import com.arb.strategy.impl.SsfBasisArb;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SsfBasisArbTest {

    private static final UnsafeBuffer         BUF     = new UnsafeBuffer(ByteBuffer.allocate(256));
    private static final MessageHeaderEncoder  HDR_ENC = new MessageHeaderEncoder();
    private static final MessageHeaderDecoder  HDR_DEC = new MessageHeaderDecoder();
    private static final MarketDataTickEncoder ENC     = new MarketDataTickEncoder();
    private static final MarketDataTickDecoder DEC     = new MarketDataTickDecoder();

    static class CaptureSink implements OrderSink {
        boolean fired = false;
        Side    side  = null;
        @Override
        public void send(String symbol, Side s, long price, long qty, OrderType orderType) {
            fired = true;
            side  = s;
        }
    }

    private static MarketDataTickDecoder buildTick(final String symbol, final long price) {
        HDR_ENC.wrap(BUF, 0)
            .blockLength(MarketDataTickEncoder.BLOCK_LENGTH)
            .templateId(MarketDataTickEncoder.TEMPLATE_ID)
            .schemaId(MarketDataTickEncoder.SCHEMA_ID)
            .version(MarketDataTickEncoder.SCHEMA_VERSION);
        ENC.wrap(BUF, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .exchange(Exchange.TAIFEX)
            .price(price)
            .qty(100L)
            .timestamp(System.nanoTime());
        HDR_DEC.wrap(BUF, 0);
        return DEC.wrap(BUF, MessageHeaderDecoder.ENCODED_LENGTH,
            HDR_DEC.blockLength(), HDR_DEC.version());
    }

    @Test
    void signalFires_whenFuturesTradeRichToFairValue() {
        // ssfPrice=6_010_000, spotPrice=5_800_000
        // spread=(210_000*1_000_000)/5_800_000 ≈ 36_206
        // carry=250*30/365=20, buffer=1_500 → carry+buffer=1_520
        // 36_206 > 1_520 → fires SELL
        final CaptureSink sink  = new CaptureSink();
        final SsfBasisArb strat = new SsfBasisArb(
            "TSMC-SSF-TW", "2330.TW", 250, 30,
            new AtomicLong(0L), 1_500L, new AtomicLong(50L));
        strat.onMarketData(buildTick("TSMC-SSF-TW", 6_010_000L), sink);
        strat.onMarketData(buildTick("2330.TW",     5_800_000L), sink);
        assertTrue(sink.fired);
        assertEquals(Side.SELL, sink.side);
    }

    @Test
    void signalSuppressed_whenFuturesPriceCloseToSpot() {
        // ssfPrice=5_802_000, spotPrice=5_800_000
        // spread = (2_000 * 1_000_000) / 5_800_000 ≈ 344
        // 344 ≤ 1_520 → no fire
        final CaptureSink sink  = new CaptureSink();
        final SsfBasisArb strat = new SsfBasisArb(
            "TSMC-SSF-TW", "2330.TW", 250, 30,
            new AtomicLong(0L), 1_500L, new AtomicLong(50L));
        strat.onMarketData(buildTick("TSMC-SSF-TW", 5_802_000L), sink);
        strat.onMarketData(buildTick("2330.TW",     5_800_000L), sink);
        assertFalse(sink.fired);
    }
}
