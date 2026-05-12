package com.arb.execution;

import com.arb.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class RiskGatewayTest {

    static class CaptureConnector extends MockExchangeConnector {
        final AtomicBoolean fillCalled   = new AtomicBoolean(false);
        final AtomicBoolean rejectCalled = new AtomicBoolean(false);
        final AtomicInteger rejectCode   = new AtomicInteger(0);

        CaptureConnector() {
            super(null, 0, 0);
        }

        @Override
        public void fill(long orderId, String symbol, Side side, long fillPrice, long fillQty,
                         long basketId, short legIndex) {
            fillCalled.set(true);
        }

        @Override
        public void reject(long orderId, String symbol, Side side, short code,
                           long basketId, short legIndex) {
            rejectCalled.set(true);
            rejectCode.set(code);
        }
    }

    private RiskConfig        config;
    private PositionBook      positions;
    private CaptureConnector  connector;
    private RiskGateway       gateway;

    private final UnsafeBuffer         buf    = new UnsafeBuffer(ByteBuffer.allocate(256));
    private final MessageHeaderEncoder hdrEnc = new MessageHeaderEncoder();
    private final OrderRequestEncoder  enc    = new OrderRequestEncoder();

    @BeforeEach
    void setUp() {
        config    = new RiskConfig(500L, 100_000L, 5_000L);
        positions = new PositionBook(16);
        connector = new CaptureConnector();
        gateway   = new RiskGateway(config, positions, connector);
    }

    private void encode(String symbol, Side side, long price, long qty, long orderId) {
        encode(symbol, side, price, qty, orderId, 0L);
    }

    private void encode(String symbol, Side side, long price, long qty, long orderId, long seqNo) {
        hdrEnc.wrap(buf, 0)
            .blockLength(OrderRequestEncoder.BLOCK_LENGTH)
            .templateId(OrderRequestEncoder.TEMPLATE_ID)
            .schemaId(OrderRequestEncoder.SCHEMA_ID)
            .version(OrderRequestEncoder.SCHEMA_VERSION);
        enc.wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .side(side)
            .price(price)
            .qty(qty)
            .orderType(OrderType.LIMIT)
            .orderId(orderId)
            .basketId(0L)
            .legIndex((short) 0)
            .seqNo(seqNo);
    }

    @Test
    void fatFinger_qty_rejectsOversizedOrder() {
        encode("HSI.HK", Side.SELL, 190_000_0000L, 501L, 1L); // 501 > 500 limit
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
        assertTrue(connector.rejectCalled.get());
        assertEquals(1, connector.rejectCode.get());
        assertFalse(connector.fillCalled.get());
    }

    @Test
    void fatFinger_price_rejectsPriceDeviation() {
        gateway.updateLastPrice("HSI.HK", 190_000_0000L);
        // 15% deviation > 10% limit
        encode("HSI.HK", Side.BUY, 218_500_0000L, 1L, 2L);
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
        assertTrue(connector.rejectCalled.get());
        assertEquals(2, connector.rejectCode.get());
    }

    @Test
    void positionLimit_rejectsOrderExceedingNetExposure() {
        positions.applyDelta("HSI.HK", 4_900L);
        // BUY 200 would push net to 5100 > 5000 limit
        encode("HSI.HK", Side.BUY, 190_000_0000L, 200L, 3L);
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
        assertTrue(connector.rejectCalled.get());
        assertEquals(3, connector.rejectCode.get());
    }

    @Test
    void validOrder_passesRiskAndRoutsToExchange() {
        encode("HSI.HK", Side.SELL, 190_000_0000L, 10L, 4L);
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
        assertTrue(connector.fillCalled.get());
        assertFalse(connector.rejectCalled.get());
    }

    @Test
    void sequencedOrders_trackLastSeqNo() {
        // seqNo=1 → 2 → 3 in order: lastOrderSeqNo should end at 3
        encode("HSI.HK", Side.SELL, 190_000_0000L, 1L, 10L, 1L);
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
        assertEquals(1L, gateway.lastOrderSeqNo());

        encode("HSI.HK", Side.BUY, 190_000_0000L, 1L, 11L, 2L);
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
        assertEquals(2L, gateway.lastOrderSeqNo());

        encode("HSI.HK", Side.SELL, 190_000_0000L, 1L, 12L, 3L);
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
        assertEquals(3L, gateway.lastOrderSeqNo());
    }

    @Test
    void sequenceGap_doesNotRejectButAdvancesSeqNo() {
        // seqNo=1 then seqNo=5 (gap of 3) — must still fill and advance lastSeqNo to 5
        encode("HSI.HK", Side.SELL, 190_000_0000L, 1L, 20L, 1L);
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);

        connector.fillCalled.set(false);
        encode("HSI.HK", Side.SELL, 190_000_0000L, 1L, 21L, 5L); // gap: expected 2, got 5
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);

        // Gap triggers a warning but the order must NOT be rejected
        assertTrue(connector.fillCalled.get(), "Order should still fill despite seqNo gap");
        assertFalse(connector.rejectCalled.get(), "Gap must not cause rejection");
        assertEquals(5L, gateway.lastOrderSeqNo());
    }

    @Test
    void unsequencedOrder_seqNoZero_neverTriggersGap() {
        // Unsequenced orders (seqNo=0) must never affect gap tracking
        encode("HSI.HK", Side.SELL, 190_000_0000L, 1L, 30L, 0L);
        gateway.onFragment(buf, 0, MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
        assertEquals(0L, gateway.lastOrderSeqNo(), "seqNo=0 should not update lastOrderSeqNo");
        assertTrue(connector.fillCalled.get());
    }
}
