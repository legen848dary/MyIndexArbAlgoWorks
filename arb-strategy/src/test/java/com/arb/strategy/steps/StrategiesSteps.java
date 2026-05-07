package com.arb.strategy.steps;

import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;
import com.arb.strategy.impl.*;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class StrategiesSteps {

    // ── SBE flyweights ────────────────────────────────────────────────────────
    private final UnsafeBuffer        buf    = new UnsafeBuffer(ByteBuffer.allocate(256));
    private final MessageHeaderEncoder hdrEnc = new MessageHeaderEncoder();
    private final MessageHeaderDecoder hdrDec = new MessageHeaderDecoder();
    private final FvUpdateEncoder      fvEnc  = new FvUpdateEncoder();
    private final FvUpdateDecoder      fvDec  = new FvUpdateDecoder();
    private final MarketDataTickEncoder mdEnc  = new MarketDataTickEncoder();
    private final MarketDataTickDecoder mdDec  = new MarketDataTickDecoder();
    private final QuoteTickEncoder     qtEnc  = new QuoteTickEncoder();
    private final QuoteTickDecoder     qtDec  = new QuoteTickDecoder();

    // ── Scenario state ────────────────────────────────────────────────────────
    private Strategy    strategy;
    private CaptureSink sink = new CaptureSink();

    // ── Capture sink ──────────────────────────────────────────────────────────
    static final class CaptureSink implements OrderSink {
        boolean fired = false;
        Side    side  = null;

        @Override
        public void send(final String symbol, final Side s,
                         final long price, final long qty, final OrderType orderType) {
            fired = true;
            side  = s;
        }
    }

    // ── Given steps ──────────────────────────────────────────────────────────

    @Given("a HkexBasisArb strategy with entry threshold {int} bps100 and exit threshold {int} bps100")
    public void givenHkexBasisArb(final int entry, final int exit) {
        strategy = new HkexBasisArb(entry, exit, 10L, new AtomicLong(10L));
    }

    @Given("a MhiHsiBasisArb strategy with threshold {int} bps100")
    public void givenMhiHsiBasisArb(final int thresh) {
        strategy = new MhiHsiBasisArb(thresh);
    }

    @Given("a TwseEtfArb strategy for symbol {word} with threshold {int} bps100")
    public void givenTwseEtfArb(final String symbol, final int thresh) {
        strategy = new TwseEtfArb(symbol, thresh, new AtomicLong(10L));
    }

    @Given("a VolSkewBasisArb strategy with base threshold {int} and IV {long} RV {long}")
    public void givenVolSkewBasisArb(final int baseThresh, final long iv, final long rv) {
        // ivRvScaleDown=10_000 so adaptiveThresh = baseThresh + (iv-rv)/10_000
        // With IV=3_000_000, RV=1_500_000: adaptive = 5_000 + 150 = 5_150 < 10_000 → fires
        strategy = new VolSkewBasisArb(
            baseThresh, 10_000L,
            new AtomicLong(iv), new AtomicLong(rv), new AtomicLong(10L));
    }

    @Given("a SsfBasisArb strategy for SSF {word} spot {word}")
    public void givenSsfBasisArb(final String ssfSym, final String spotSym) {
        strategy = new SsfBasisArb(
            ssfSym, spotSym, 250, 30, new AtomicLong(0L), 1_500L, new AtomicLong(50L));
    }

    @Given("a HkCnIndexPairArb strategy for {word} and {word} with entry z-score {int}")
    public void givenHkCnIndexPairArb(final String hkSym, final String cnSym, final int entryZ) {
        strategy = new HkCnIndexPairArb(
            hkSym, cnSym,
            new AtomicLong(8_000L),   // beta = 0.8
            new AtomicLong(0L),        // mean = 0
            new AtomicLong(10_000L),   // sigma
            entryZ, entryZ / 2);
    }

    @Given("a CrossBorderEtfArb strategy for symbol {word} with threshold {int} and fx rate {long}")
    public void givenCrossBorderEtfArb(final String symbol, final int thresh, final long fxRate) {
        strategy = new CrossBorderEtfArb(symbol, thresh, new AtomicLong(fxRate));
    }

    // ── When steps ────────────────────────────────────────────────────────────

    @When("an FvUpdate arrives with annualisedBasisBps {int}")
    public void whenFvUpdateBps(final int bps) {
        hdrEnc.wrap(buf, 0)
            .blockLength(FvUpdateEncoder.BLOCK_LENGTH)
            .templateId(FvUpdateEncoder.TEMPLATE_ID)
            .schemaId(FvUpdateEncoder.SCHEMA_ID)
            .version(FvUpdateEncoder.SCHEMA_VERSION);
        fvEnc.wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol("HSI.HK")
            .exchange(Exchange.HKEX)
            .navPerUnit(1_900_000_000L)
            .futuresFv(1_900_000_000L)
            .basis(0L)
            .annualisedBasisBps(bps)
            .timestamp(System.nanoTime());
        hdrDec.wrap(buf, 0);
        strategy.onFvUpdate(
            fvDec.wrap(buf, MessageHeaderDecoder.ENCODED_LENGTH,
                hdrDec.blockLength(), hdrDec.version()),
            sink);
    }

    @When("a MarketDataTick arrives for {word} with price {long}")
    public void whenMarketDataTick(final String symbol, final long price) {
        hdrEnc.wrap(buf, 0)
            .blockLength(MarketDataTickEncoder.BLOCK_LENGTH)
            .templateId(MarketDataTickEncoder.TEMPLATE_ID)
            .schemaId(MarketDataTickEncoder.SCHEMA_ID)
            .version(MarketDataTickEncoder.SCHEMA_VERSION);
        mdEnc.wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .exchange(Exchange.HKEX)
            .price(price)
            .qty(1_000L)
            .timestamp(System.nanoTime());
        hdrDec.wrap(buf, 0);
        strategy.onMarketData(
            mdDec.wrap(buf, MessageHeaderDecoder.ENCODED_LENGTH,
                hdrDec.blockLength(), hdrDec.version()),
            sink);
    }

    @When("a QuoteTick arrives for {word} with IEP {long}")
    public void whenQuoteTick(final String symbol, final long iep) {
        hdrEnc.wrap(buf, 0)
            .blockLength(QuoteTickEncoder.BLOCK_LENGTH)
            .templateId(QuoteTickEncoder.TEMPLATE_ID)
            .schemaId(QuoteTickEncoder.SCHEMA_ID)
            .version(QuoteTickEncoder.SCHEMA_VERSION);
        qtEnc.wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .exchange(Exchange.TAIFEX)
            .iep(iep)
            .bidPrice(iep - 5_000L)
            .askPrice(iep + 5_000L)
            .timestamp(System.nanoTime());
        hdrDec.wrap(buf, 0);
        strategy.onQuote(
            qtDec.wrap(buf, MessageHeaderDecoder.ENCODED_LENGTH,
                hdrDec.blockLength(), hdrDec.version()),
            sink);
    }

    @When("an FvUpdate arrives for {word} with navPerUnit {long}")
    public void whenFvUpdateNav(final String symbol, final long nav) {
        whenFvUpdateNavFv(symbol, nav, nav);
    }

    @When("an FvUpdate arrives for {word} with navPerUnit {long} futuresFv {long}")
    public void whenFvUpdateNavFv(final String symbol, final long nav, final long futuresFv) {
        hdrEnc.wrap(buf, 0)
            .blockLength(FvUpdateEncoder.BLOCK_LENGTH)
            .templateId(FvUpdateEncoder.TEMPLATE_ID)
            .schemaId(FvUpdateEncoder.SCHEMA_ID)
            .version(FvUpdateEncoder.SCHEMA_VERSION);
        fvEnc.wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol(symbol)
            .exchange(Exchange.HKEX)
            .navPerUnit(nav)
            .futuresFv(futuresFv)
            .basis(0L)
            .annualisedBasisBps(0L)
            .timestamp(System.nanoTime());
        hdrDec.wrap(buf, 0);
        strategy.onFvUpdate(
            fvDec.wrap(buf, MessageHeaderDecoder.ENCODED_LENGTH,
                hdrDec.blockLength(), hdrDec.version()),
            sink);
    }

    // ── Then steps ────────────────────────────────────────────────────────────

    @Then("the strategy should emit a SELL order")
    public void thenEmitSell() {
        assertTrue(sink.fired, "Expected a SELL order to be emitted");
        assertEquals(Side.SELL, sink.side);
    }

    @Then("the strategy should emit an order")
    public void thenEmitAnyOrder() {
        assertTrue(sink.fired, "Expected an order to be emitted");
    }

    @Then("no order should be emitted")
    public void thenNoOrder() {
        assertFalse(sink.fired, "Expected no order but one was emitted");
    }
}
