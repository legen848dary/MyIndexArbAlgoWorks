package com.arb.strategy;

import com.arb.sbe.*;
import com.arb.strategy.impl.SsfCalendarSpreadArb;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SsfCalendarSpreadArbTest {

    private static final UnsafeBuffer         BUF     = new UnsafeBuffer(ByteBuffer.allocate(256));
    private static final MessageHeaderEncoder  HDR_ENC = new MessageHeaderEncoder();
    private static final MessageHeaderDecoder  HDR_DEC = new MessageHeaderDecoder();
    private static final MarketDataTickEncoder ENC     = new MarketDataTickEncoder();
    private static final MarketDataTickDecoder DEC     = new MarketDataTickDecoder();

    static class CaptureSink implements OrderSink {
        boolean fired = false;
        @Override
        public void send(String symbol, Side s, long price, long qty, OrderType orderType) {
            fired = true;
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
    void signalFires_whenObservedSpreadDeviatesBeyondSigma() {
        // nearSymbol="NEAR-SSF", farSymbol="FAR-SSF", nearDays=30, farDays=90
        // theoreticalSpreadFactor = (90-30)*250/365 = 15000/365 = 41
        // nearPrice=5_800_000: theoreticalSpread = 5_800_000 * 41 / 10_000 = 23_780
        // farPrice=6_010_000: observedSpread = 210_000
        // |210_000 - 23_780| = 186_220, sigma=500, mult=2 → 186_220 > 1_000 → fires
        final CaptureSink          sink  = new CaptureSink();
        final SsfCalendarSpreadArb strat = new SsfCalendarSpreadArb(
            "NEAR-SSF", "FAR-SSF", 250, 30, 90, new AtomicLong(500L), 2L);
        strat.onMarketData(buildTick("NEAR-SSF", 5_800_000L), sink);
        strat.onMarketData(buildTick("FAR-SSF",  6_010_000L), sink);
        assertTrue(sink.fired);
    }

    @Test
    void signalSuppressed_whenObservedSpreadMatchesTheoretical() {
        // nearPrice=5_800_000: theoreticalSpread=23_780
        // farPrice=5_823_880: observedSpread=23_880
        // |23_880 - 23_780| = 100, sigma=500, mult=2 → 100 ≤ 1_000 → no fire
        final CaptureSink          sink  = new CaptureSink();
        final SsfCalendarSpreadArb strat = new SsfCalendarSpreadArb(
            "NEAR-SSF", "FAR-SSF", 250, 30, 90, new AtomicLong(500L), 2L);
        strat.onMarketData(buildTick("NEAR-SSF", 5_800_000L), sink);
        strat.onMarketData(buildTick("FAR-SSF",  5_823_880L), sink);
        assertFalse(sink.fired);
    }
}
