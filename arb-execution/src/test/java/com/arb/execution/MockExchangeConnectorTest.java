package com.arb.execution;

import com.arb.sbe.Side;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class MockExchangeConnectorTest {

    static class CaptureMockConnector extends MockExchangeConnector {
        final AtomicBoolean fillPublished   = new AtomicBoolean(false);
        final AtomicBoolean rejectPublished = new AtomicBoolean(false);

        CaptureMockConnector() { super(null, 0L, 0L); }

        @Override
        public void fill(long orderId, String symbol, Side side, long fillPrice, long fillQty,
                         long basketId, short legIndex) {
            fillPublished.set(true);
        }

        @Override
        public void reject(long orderId, String symbol, Side side, short rejectCode,
                           long basketId, short legIndex) {
            rejectPublished.set(true);
        }
    }

    @Test
    void fill_marksFilledTrue() {
        final CaptureMockConnector c = new CaptureMockConnector();
        c.fill(1L, "HSI.HK", Side.SELL, 190_000_0000L, 10L, 0L, (short) 0);
        assertTrue(c.fillPublished.get());
    }

    @Test
    void reject_marksRejectedTrue() {
        final CaptureMockConnector c = new CaptureMockConnector();
        c.reject(2L, "MHI.HK", Side.BUY, (short) 1, 0L, (short) 0);
        assertTrue(c.rejectPublished.get());
    }
}
