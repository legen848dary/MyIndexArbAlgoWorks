package com.arb.strategy;

import com.arb.sbe.*;
import com.arb.strategy.impl.MhiHsiBasisArb;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class MhiHsiBasisArbTest {

    private static final UnsafeBuffer        BUF     = new UnsafeBuffer(ByteBuffer.allocate(256));
    private static final MessageHeaderEncoder HDR_ENC = new MessageHeaderEncoder();
    private static final MessageHeaderDecoder HDR_DEC = new MessageHeaderDecoder();
    private static final MarketDataTickEncoder ENC    = new MarketDataTickEncoder();
    private static final MarketDataTickDecoder DEC    = new MarketDataTickDecoder();

    static class CaptureSink implements OrderSink {
        boolean fired = false;
        @Override
        public void send(String symbol, Side s, long price, long qty, OrderType orderType) {
            fired = true;
        }
    }

    private static MarketDataTickDecoder buildTick(final String symbol,
                                                    final Exchange exchange,
                                                    final long price) {
        HDR_ENC.wrap(BUF, 0)
            .blockLength(MarketDataTickEncoder.BLOCK_LENGTH)
            .templateId(MarketDataTickEncoder.TEMPLATE_ID)
            .schemaId(MarketDataTickEncoder.SCHEMA_ID)
            .version(MarketDataTickEncoder.SCHEMA_VERSION);
        ENC.wrap(BUF, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .exchange(exchange)
            .price(price)
            .qty(1_000L)
            .timestamp(System.nanoTime());
        HDR_DEC.wrap(BUF, 0);
        return DEC.wrap(BUF, MessageHeaderDecoder.ENCODED_LENGTH,
            HDR_DEC.blockLength(), HDR_DEC.version());
    }

    @Test
    void signalFires_whenSpreadExceedsThreshold() {
        // HSI=19000 (190_000_000), MHI=3700 (37_000_000)
        // spread = 190_000_000 - 5*37_000_000 = 5_000_000
        // spreadBps100 = 5_000_000 * 1_000_000 / 190_000_000 ≈ 26_315 > threshold 200
        final CaptureSink     sink  = new CaptureSink();
        final MhiHsiBasisArb  strat = new MhiHsiBasisArb(200L);
        strat.onMarketData(buildTick("HSI.HK", Exchange.HKEX, 190_000_000L), sink);
        strat.onMarketData(buildTick("MHI.HK", Exchange.HKEX,  37_000_000L), sink);
        assertTrue(sink.fired);
    }

    @Test
    void signalSuppressed_whenSpreadBelowThreshold() {
        // MHI=38000000 → 5*38000000 = 190_000_000 = HSI → spread = 0
        final CaptureSink    sink  = new CaptureSink();
        final MhiHsiBasisArb strat = new MhiHsiBasisArb(200L);
        strat.onMarketData(buildTick("HSI.HK", Exchange.HKEX, 190_000_000L), sink);
        strat.onMarketData(buildTick("MHI.HK", Exchange.HKEX,  38_000_000L), sink);
        assertFalse(sink.fired);
    }
}
