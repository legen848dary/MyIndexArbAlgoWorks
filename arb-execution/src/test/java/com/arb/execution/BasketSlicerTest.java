package com.arb.execution;

import com.arb.sbe.OrderType;
import com.arb.sbe.Side;
import com.arb.strategy.OrderSink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class BasketSlicerTest {

    static class CountingSink implements OrderSink {
        final AtomicInteger count = new AtomicInteger(0);
        String lastSymbol;
        @Override
        public void send(String symbol, Side side, long price, long qty, OrderType orderType) {
            count.incrementAndGet();
            lastSymbol = symbol;
        }
    }

    @Test
    void slice_threeLegs_sendsThreeOrders() {
        final CountingSink sink   = new CountingSink();
        final BasketSlicer slicer = new BasketSlicer();
        slicer.slice(List.of(
            new BasketSlicer.BasketLeg("HSI.HK",   Side.SELL, 190_000_0000L, 1L, OrderType.LIMIT),
            new BasketSlicer.BasketLeg("MHI.HK",   Side.BUY,  38_000_0000L,  5L, OrderType.LIMIT),
            new BasketSlicer.BasketLeg("2330.TW",  Side.BUY,  580_0000L,     2L, OrderType.MARKET)
        ), sink);
        assertEquals(3, sink.count.get());
    }

    @Test
    void slice_singleLeg_correctSymbol() {
        final CountingSink sink   = new CountingSink();
        final BasketSlicer slicer = new BasketSlicer();
        slicer.slice(List.of(
            new BasketSlicer.BasketLeg("0050.TW", Side.BUY, 180_0000L, 10L, OrderType.LIMIT)
        ), sink);
        assertEquals("0050.TW", sink.lastSymbol);
    }

    @Test
    void slice_emptyBasket_noOrders() {
        final CountingSink sink   = new CountingSink();
        final BasketSlicer slicer = new BasketSlicer();
        slicer.slice(List.of(), sink);
        assertEquals(0, sink.count.get());
    }
}
