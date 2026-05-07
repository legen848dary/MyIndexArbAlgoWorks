package com.arb.strategy;

import com.arb.sbe.*;
import com.arb.strategy.impl.TwseEtfArb;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TwseEtfArbTest {

    private static final UnsafeBuffer        BUF      = new UnsafeBuffer(ByteBuffer.allocate(256));
    private static final MessageHeaderEncoder HDR_ENC  = new MessageHeaderEncoder();
    private static final MessageHeaderDecoder HDR_DEC  = new MessageHeaderDecoder();
    private static final QuoteTickEncoder     Q_ENC    = new QuoteTickEncoder();
    private static final QuoteTickDecoder     Q_DEC    = new QuoteTickDecoder();
    private static final FvUpdateEncoder      FV_ENC   = new FvUpdateEncoder();
    private static final FvUpdateDecoder      FV_DEC   = new FvUpdateDecoder();

    static class CaptureSink implements OrderSink {
        boolean fired = false;
        @Override
        public void send(String symbol, Side s, long price, long qty, OrderType orderType) {
            fired = true;
        }
    }

    private static QuoteTickDecoder buildQuote(final String symbol, final long iep) {
        HDR_ENC.wrap(BUF, 0)
            .blockLength(QuoteTickEncoder.BLOCK_LENGTH)
            .templateId(QuoteTickEncoder.TEMPLATE_ID)
            .schemaId(QuoteTickEncoder.SCHEMA_ID)
            .version(QuoteTickEncoder.SCHEMA_VERSION);
        Q_ENC.wrap(BUF, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .exchange(Exchange.TAIFEX)
            .iep(iep)
            .bidPrice(iep - 5_000L)
            .askPrice(iep + 5_000L)
            .timestamp(System.nanoTime());
        HDR_DEC.wrap(BUF, 0);
        return Q_DEC.wrap(BUF, MessageHeaderDecoder.ENCODED_LENGTH,
            HDR_DEC.blockLength(), HDR_DEC.version());
    }

    private static FvUpdateDecoder buildFv(final String symbol, final long nav) {
        HDR_ENC.wrap(BUF, 0)
            .blockLength(FvUpdateEncoder.BLOCK_LENGTH)
            .templateId(FvUpdateEncoder.TEMPLATE_ID)
            .schemaId(FvUpdateEncoder.SCHEMA_ID)
            .version(FvUpdateEncoder.SCHEMA_VERSION);
        FV_ENC.wrap(BUF, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .exchange(Exchange.TAIFEX)
            .navPerUnit(nav)
            .futuresFv(nav)
            .basis(0L)
            .annualisedBasisBps(0L)
            .timestamp(System.nanoTime());
        HDR_DEC.wrap(BUF, 0);
        return FV_DEC.wrap(BUF, MessageHeaderDecoder.ENCODED_LENGTH,
            HDR_DEC.blockLength(), HDR_DEC.version());
    }

    @Test
    void signalFires_whenMarketPriceDivergesFromNav() {
        // etfMarketPrice=18_000_000, nav=17_000_000
        // diff=1_000_000, threshold = 2_000 * 17_000_000 / 1_000_000 = 34_000
        // 1_000_000 > 34_000 → fires
        final CaptureSink sink  = new CaptureSink();
        final TwseEtfArb  strat = new TwseEtfArb("0050.TW", 2_000L, new AtomicLong(10L));
        strat.onQuote(buildQuote("0050.TW", 18_000_000L), sink);
        strat.onFvUpdate(buildFv("0050.TW", 17_000_000L), sink);
        assertTrue(sink.fired);
    }

    @Test
    void signalSuppressed_whenMarketPriceCloseToNav() {
        // etfMarketPrice=17_010_000, nav=17_000_000
        // diff=10_000, threshold=34_000 → 10_000 ≤ 34_000 → no fire
        final CaptureSink sink  = new CaptureSink();
        final TwseEtfArb  strat = new TwseEtfArb("0050.TW", 2_000L, new AtomicLong(10L));
        strat.onQuote(buildQuote("0050.TW", 17_010_000L), sink);
        strat.onFvUpdate(buildFv("0050.TW", 17_000_000L), sink);
        assertFalse(sink.fired);
    }
}
