package com.arb.strategy;

import com.arb.sbe.*;
import com.arb.strategy.impl.CrossBorderEtfArb;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CrossBorderEtfArbTest {

    private static final UnsafeBuffer        BUF     = new UnsafeBuffer(ByteBuffer.allocate(256));
    private static final MessageHeaderEncoder HDR_ENC = new MessageHeaderEncoder();
    private static final MessageHeaderDecoder HDR_DEC = new MessageHeaderDecoder();
    private static final FvUpdateEncoder      ENC     = new FvUpdateEncoder();
    private static final FvUpdateDecoder      DEC     = new FvUpdateDecoder();

    static class CaptureSink implements OrderSink {
        boolean fired = false;
        @Override
        public void send(String symbol, Side s, long price, long qty, OrderType orderType) {
            fired = true;
        }
    }

    private static FvUpdateDecoder buildFv(final String symbol,
                                            final long nav,
                                            final long futuresFv) {
        HDR_ENC.wrap(BUF, 0)
            .blockLength(FvUpdateEncoder.BLOCK_LENGTH)
            .templateId(FvUpdateEncoder.TEMPLATE_ID)
            .schemaId(FvUpdateEncoder.SCHEMA_ID)
            .version(FvUpdateEncoder.SCHEMA_VERSION);
        ENC.wrap(BUF, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .exchange(Exchange.HKEX)
            .navPerUnit(nav)
            .futuresFv(futuresFv)
            .basis(0L)
            .annualisedBasisBps(0L)
            .timestamp(System.nanoTime());
        HDR_DEC.wrap(BUF, 0);
        return DEC.wrap(BUF, MessageHeaderDecoder.ENCODED_LENGTH,
            HDR_DEC.blockLength(), HDR_DEC.version());
    }

    @Test
    void signalFires_whenCnhAdjustedBasisExceedsThreshold() {
        // fxRate=91000, nav=10_000_000, futuresFv=10_500_000
        // navCnh = 10_000_000 * 91_000 / 100_000 = 9_100_000
        // cnhBasis = (10_500_000 - 9_100_000) * 1_000_000 / 9_100_000 ≈ 153_846
        // threshold=3_000 → 153_846 > 3_000 → fires
        final CaptureSink       sink  = new CaptureSink();
        final CrossBorderEtfArb strat =
            new CrossBorderEtfArb("2822.HK", 3_000L, new AtomicLong(91_000L));
        strat.onFvUpdate(buildFv("2822.HK", 10_000_000L, 10_500_000L), sink);
        assertTrue(sink.fired);
    }

    @Test
    void signalSuppressed_whenBasisBelowThreshold() {
        // futuresFv = navCnh = 9_100_000 → cnhBasis = 0 ≤ threshold → no fire
        final CaptureSink       sink  = new CaptureSink();
        final CrossBorderEtfArb strat =
            new CrossBorderEtfArb("2822.HK", 3_000L, new AtomicLong(91_000L));
        strat.onFvUpdate(buildFv("2822.HK", 10_000_000L, 9_100_000L), sink);
        assertFalse(sink.fired);
    }
}
