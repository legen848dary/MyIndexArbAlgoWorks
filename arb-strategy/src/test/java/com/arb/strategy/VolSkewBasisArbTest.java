package com.arb.strategy;

import com.arb.sbe.*;
import com.arb.strategy.impl.VolSkewBasisArb;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class VolSkewBasisArbTest {

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

    private static FvUpdateDecoder buildFv(final long annualisedBasisBps) {
        HDR_ENC.wrap(BUF, 0)
            .blockLength(FvUpdateEncoder.BLOCK_LENGTH)
            .templateId(FvUpdateEncoder.TEMPLATE_ID)
            .schemaId(FvUpdateEncoder.SCHEMA_ID)
            .version(FvUpdateEncoder.SCHEMA_VERSION);
        ENC.wrap(BUF, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol("HSI.HK")
            .exchange(Exchange.HKEX)
            .navPerUnit(1_900_000_000L)
            .futuresFv(1_900_000_000L)
            .basis(0L)
            .annualisedBasisBps(annualisedBasisBps)
            .timestamp(System.nanoTime());
        HDR_DEC.wrap(BUF, 0);
        return DEC.wrap(BUF, MessageHeaderDecoder.ENCODED_LENGTH,
            HDR_DEC.blockLength(), HDR_DEC.version());
    }

    @Test
    void signalFires_whenBasisExceedsAdaptiveThreshold() {
        // IV=3_000_000, RV=1_500_000, scaleDown=10
        // adaptiveThresh = 5_000 + (1_500_000/10) = 155_000
        // bps=160_000 > 155_000 → fires
        final CaptureSink    sink  = new CaptureSink();
        final VolSkewBasisArb strat = new VolSkewBasisArb(
            5_000L, 10L,
            new AtomicLong(3_000_000L),
            new AtomicLong(1_500_000L),
            new AtomicLong(10L));
        strat.onFvUpdate(buildFv(160_000L), sink);
        assertTrue(sink.fired);
    }

    @Test
    void signalSuppressed_whenBasisBelowAdaptiveThreshold() {
        // adaptiveThresh = 155_000; bps=100_000 ≤ 155_000 → no fire
        final CaptureSink    sink  = new CaptureSink();
        final VolSkewBasisArb strat = new VolSkewBasisArb(
            5_000L, 10L,
            new AtomicLong(3_000_000L),
            new AtomicLong(1_500_000L),
            new AtomicLong(10L));
        strat.onFvUpdate(buildFv(100_000L), sink);
        assertFalse(sink.fired);
    }
}
