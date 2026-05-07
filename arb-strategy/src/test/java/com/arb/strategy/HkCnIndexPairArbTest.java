package com.arb.strategy;

import com.arb.sbe.*;
import com.arb.strategy.impl.HkCnIndexPairArb;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HkCnIndexPairArbTest {

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
            .exchange(Exchange.HKEX)
            .price(price)
            .qty(1_000L)
            .timestamp(System.nanoTime());
        HDR_DEC.wrap(BUF, 0);
        return DEC.wrap(BUF, MessageHeaderDecoder.ENCODED_LENGTH,
            HDR_DEC.blockLength(), HDR_DEC.version());
    }

    @Test
    void signalFires_whenZScoreBreachesEntryThreshold() {
        // beta=10_000 (β=1.0), mean=0, sigma=10_000
        // hkPrice=100_000_000, cnPrice=80_000_000
        // spread = 100_000_000 - 1.0 * 80_000_000 = 20_000_000
        // zScore100 = 20_000_000 * 100 / 10_000 = 200_000
        // entryZScore100=200, exitZScore100=100 → |200_000| > 200 → fires
        final CaptureSink       sink  = new CaptureSink();
        final HkCnIndexPairArb  strat = new HkCnIndexPairArb(
            "HSI.HK", "CSI300.CN",
            new AtomicLong(10_000L),  // beta = 1.0
            new AtomicLong(0L),       // mean = 0
            new AtomicLong(10_000L),  // sigma
            200L, 100L);
        strat.onMarketData(buildTick("HSI.HK",    100_000_000L), sink);
        strat.onMarketData(buildTick("CSI300.CN",  80_000_000L), sink);
        assertTrue(sink.fired);
    }

    @Test
    void signalSuppressed_whenZScoreBelowExitThreshold() {
        // hkPrice = cnPrice → spread = 0 → zScore100 = 0 < exitZScore100=100
        final CaptureSink       sink  = new CaptureSink();
        final HkCnIndexPairArb  strat = new HkCnIndexPairArb(
            "HSI.HK", "CSI300.CN",
            new AtomicLong(10_000L),
            new AtomicLong(0L),
            new AtomicLong(10_000L),
            200L, 100L);
        strat.onMarketData(buildTick("HSI.HK",    100_000_000L), sink);
        strat.onMarketData(buildTick("CSI300.CN", 100_000_000L), sink);
        assertFalse(sink.fired);
    }
}
