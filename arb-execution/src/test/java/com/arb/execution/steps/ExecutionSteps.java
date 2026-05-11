package com.arb.execution.steps;

import com.arb.execution.*;
import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ExecutionSteps {

    static class CaptureConnector extends MockExchangeConnector {
        final AtomicBoolean fillCalled   = new AtomicBoolean(false);
        final AtomicBoolean rejectCalled = new AtomicBoolean(false);
        final AtomicInteger rejectCode   = new AtomicInteger(0);
        CaptureConnector() { super(null, 0, 0); }
        @Override public void fill(long id, String sym, Side s, long p, long q, long basketId, short legIndex) { fillCalled.set(true); }
        @Override public void reject(long id, String sym, Side s, short code, long basketId, short legIndex) { rejectCalled.set(true); rejectCode.set(code); }
    }

    static class CountingSink implements OrderSink {
        final AtomicInteger count = new AtomicInteger(0);
        @Override public void send(String s, Side side, long p, long q, OrderType t) { count.incrementAndGet(); }
    }

    private RiskGateway      gateway;
    private CaptureConnector connector;
    private PositionBook     positions;
    private CountingSink     sink;
    private BasketSlicer     slicer;

    private final UnsafeBuffer         buf    = new UnsafeBuffer(ByteBuffer.allocate(256));
    private final MessageHeaderEncoder hdrEnc = new MessageHeaderEncoder();
    private final OrderRequestEncoder  enc    = new OrderRequestEncoder();

    @Before
    public void reset() {
        connector = null;
        gateway   = null;
        positions = null;
        sink      = new CountingSink();
        slicer    = new BasketSlicer();
    }

    @Given("a RiskGateway with maxQtyPerOrder {long} lots")
    public void a_risk_gateway(final long maxQty) {
        positions = new PositionBook(16);
        connector = new CaptureConnector();
        gateway   = new RiskGateway(new RiskConfig(maxQty, 100_000L, 5_000L), positions, connector);
    }

    @Given("the last known price for {word} is {long}")
    public void last_known_price(final String symbol, final long price) {
        gateway.updateLastPrice(symbol, price);
    }

    @Given("the current net position for {word} is {long} lots")
    public void current_position(final String symbol, final long lots) {
        positions.applyDelta(symbol, lots);
    }

    @When("an order is submitted for {word} {word} {long} lots at price {long}")
    public void submit_order(final String symbol, final String sideStr, final long qty, final long price) {
        final Side side = "BUY".equalsIgnoreCase(sideStr) ? Side.BUY : Side.SELL;
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
            .orderId(99L);
        gateway.onFragment(buf, 0,
            MessageHeaderDecoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH, null);
    }

    @Then("the order should be rejected with code {int}")
    public void order_rejected(final int code) {
        assertTrue(connector.rejectCalled.get());
        assertEquals(code, connector.rejectCode.get());
    }

    @Then("the order should be filled")
    public void order_filled() {
        assertTrue(connector.fillCalled.get());
        assertFalse(connector.rejectCalled.get());
    }

    @Given("a BasketSlicer")
    public void a_basket_slicer() {
        slicer = new BasketSlicer();
    }

    @When("a basket order is submitted with {int} legs")
    public void basket_submitted(final int legCount) {
        final List<BasketSlicer.BasketLeg> legs = List.of(
            new BasketSlicer.BasketLeg("HSI.HK",   Side.SELL, 190_000_0000L, 1L, OrderType.LIMIT),
            new BasketSlicer.BasketLeg("MHI.HK",   Side.BUY,  38_000_0000L,  5L, OrderType.LIMIT),
            new BasketSlicer.BasketLeg("2330.TW",  Side.BUY,  580_0000L,     2L, OrderType.MARKET)
        ).subList(0, legCount);
        slicer.slice(legs, sink);
    }

    @Then("{int} individual orders should be dispatched")
    public void orders_dispatched(final int expected) {
        assertEquals(expected, sink.count.get());
    }
}
