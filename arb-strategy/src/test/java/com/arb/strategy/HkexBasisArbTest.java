package com.arb.strategy;

import com.arb.sbe.*;
import com.arb.strategy.impl.HkexBasisArb;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HkexBasisArbTest {

    private static final UnsafeBuffer        BUF     = new UnsafeBuffer(ByteBuffer.allocate(256));
    private static final MessageHeaderEncoder HDR_ENC = new MessageHeaderEncoder();
    private static final MessageHeaderDecoder HDR_DEC = new MessageHeaderDecoder();
    private static final FvUpdateEncoder      ENC     = new FvUpdateEncoder();
    private static final FvUpdateDecoder      DEC     = new FvUpdateDecoder();

    static class CaptureSink implements OrderSink {
        boolean fired = false;
        Side    side  = null;
        @Override
        public void send(String symbol, Side s, long price, long qty, OrderType orderType) {
            fired = true;
            side  = s;
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
            .basis(5_000_000L)
            .annualisedBasisBps(annualisedBasisBps)
            .timestamp(System.nanoTime());
        HDR_DEC.wrap(BUF, 0);
        return DEC.wrap(BUF, MessageHeaderDecoder.ENCODED_LENGTH,
            HDR_DEC.blockLength(), HDR_DEC.version());
    }

    @Test
    void signalFires_whenBasisAboveExitThresh() {
        final CaptureSink  sink  = new CaptureSink();
        final HkexBasisArb strat = new HkexBasisArb(5_000L, 1_000L, 10L, new AtomicLong(10L));
        strat.onFvUpdate(buildFv(32_017L), sink);
        assertTrue(sink.fired);
        assertEquals(Side.SELL, sink.side);
    }

    @Test
    void signalSuppressed_whenBasisBelowExitThresh() {
        final CaptureSink  sink  = new CaptureSink();
        final HkexBasisArb strat = new HkexBasisArb(5_000L, 1_000L, 10L, new AtomicLong(10L));
        strat.onFvUpdate(buildFv(500L), sink);
        assertFalse(sink.fired);
    }
}
