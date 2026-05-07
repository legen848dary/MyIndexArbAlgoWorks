package com.arb.marketdata.steps;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.marketdata.gateway.MarketVolumeGateway;
import com.arb.marketdata.gateway.QuoteGateway;
import com.arb.marketdata.gateway.ReferenceDataGateway;
import com.arb.marketdata.handler.CsiQuoteHandler;
import com.arb.marketdata.handler.HkexQuoteHandler;
import com.arb.marketdata.handler.ReferenceDataHandler;
import com.arb.marketdata.handler.TaifexMarketVolumeHandler;
import com.arb.sbe.*;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ExtendedMarketDataSteps {

    private MediaDriver driver;
    private Aeron       aeron;
    private Publication publication;
    private Subscription subscription;

    private QuoteGateway         quoteGateway;
    private MarketVolumeGateway  volumeGateway;
    private ReferenceDataGateway refDataGateway;
    private AeronSubscriber      subscriber;

    // Captured state — reset in @After
    private final AtomicBoolean messageReceived = new AtomicBoolean(false);

    // Quote fields
    private final AtomicLong              capturedIep      = new AtomicLong();
    private final AtomicLong              capturedBid      = new AtomicLong();
    private final AtomicLong              capturedAsk      = new AtomicLong();
    private final AtomicReference<String> capturedExchange = new AtomicReference<>();

    // Volume fields
    private final AtomicLong capturedIev         = new AtomicLong();
    private final AtomicLong capturedDailyVolume = new AtomicLong();

    // Reference data fields
    private final AtomicLong capturedLotSize           = new AtomicLong();
    private final AtomicLong capturedTickSize          = new AtomicLong();
    private final AtomicLong capturedConstituentWeight = new AtomicLong();

    // Pre-allocated SBE flyweight decoders
    private final MessageHeaderDecoder          headerDecoder  = new MessageHeaderDecoder();
    private final QuoteTickDecoder              quoteDecoder   = new QuoteTickDecoder();
    private final MarketVolumeTickDecoder       volDecoder     = new MarketVolumeTickDecoder();
    private final ReferenceDataRecordDecoder    refDataDecoder = new ReferenceDataRecordDecoder();

    @Before
    public void setUp() {
        final MediaDriver.Context ctx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);
        driver = MediaDriver.launchEmbedded(ctx);
        aeron  = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
    }

    @After
    public void tearDown() {
        if (quoteGateway    != null) quoteGateway.close();
        if (volumeGateway   != null) volumeGateway.close();
        if (refDataGateway  != null) refDataGateway.close();
        if (subscriber      != null) subscriber.close();
        if (subscription    != null && !subscription.isClosed()) subscription.close();
        if (publication     != null && !publication.isClosed())  publication.close();
        if (aeron  != null) aeron.close();
        if (driver != null) driver.close();
        messageReceived.set(false);
    }

    // ── Given steps ──────────────────────────────────────────────────────────

    @Given("the QuoteGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL")
    public void quoteGatewayRunning() {
        publication  = aeron.addPublication(Channels.CHANNEL, Channels.MARKET_DATA_STREAM);
        subscription = aeron.addSubscription(Channels.CHANNEL, Channels.MARKET_DATA_STREAM);
        while (!publication.isConnected()) { Thread.onSpinWait(); }
        quoteGateway = new QuoteGateway(new AeronPublisher(publication));
        subscriber   = new AeronSubscriber(subscription);
    }

    @Given("the MarketVolumeGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL")
    public void volumeGatewayRunning() {
        publication  = aeron.addPublication(Channels.CHANNEL, Channels.MARKET_DATA_STREAM);
        subscription = aeron.addSubscription(Channels.CHANNEL, Channels.MARKET_DATA_STREAM);
        while (!publication.isConnected()) { Thread.onSpinWait(); }
        volumeGateway = new MarketVolumeGateway(new AeronPublisher(publication));
        subscriber    = new AeronSubscriber(subscription);
    }

    @Given("the ReferenceDataGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL")
    public void refDataGatewayRunning() {
        publication  = aeron.addPublication(Channels.CHANNEL, Channels.MARKET_DATA_STREAM);
        subscription = aeron.addSubscription(Channels.CHANNEL, Channels.MARKET_DATA_STREAM);
        while (!publication.isConnected()) { Thread.onSpinWait(); }
        refDataGateway = new ReferenceDataGateway(new AeronPublisher(publication));
        subscriber     = new AeronSubscriber(subscription);
    }

    // ── When steps ───────────────────────────────────────────────────────────

    @When("a quote arrives from HKEX for symbol {string} with IEP {double} bid {double} ask {double}")
    public void hkexQuoteArrives(final String symbol, final double iep, final double bid, final double ask) {
        new HkexQuoteHandler(quoteGateway).onQuote(symbol, iep, bid, ask);
        drainQuote();
    }

    @When("a volume tick arrives from TAIFEX for symbol {string} with IEV {long} and daily volume {long}")
    public void taifexVolumeArrives(final String symbol, final long iev, final long dailyVolume) {
        new TaifexMarketVolumeHandler(volumeGateway).onVolume(symbol, iev, dailyVolume);
        drainVolume();
    }

    @When("a reference data record arrives for symbol {string} on CSI with lot size {long} tick size {long} and constituent weight {long}")
    public void csiRefDataArrives(final String symbol, final long lotSize, final long tickSize, final long weight) {
        new ReferenceDataHandler(refDataGateway)
            .onRecord(symbol, Exchange.CSI, lotSize, tickSize, "CNY", weight);
        drainRefData();
    }

    // ── Then / And steps ─────────────────────────────────────────────────────

    @Then("a QuoteTick SBE message is received on the channel")
    public void quoteMsgReceived() {
        assertTrue(messageReceived.get(), "Expected a QuoteTick on the Aeron channel");
    }

    @And("the IEP field is {long}")
    public void iepIs(final long expected) {
        assertEquals(expected, capturedIep.get(), "IEP mismatch");
    }

    @And("the bid price field is {long}")
    public void bidIs(final long expected) {
        assertEquals(expected, capturedBid.get(), "Bid price mismatch");
    }

    @And("the ask price field is {long}")
    public void askIs(final long expected) {
        assertEquals(expected, capturedAsk.get(), "Ask price mismatch");
    }

    @And("the quote exchange field is {string}")
    public void quoteExchangeIs(final String expected) {
        assertEquals(expected, capturedExchange.get(), "Exchange mismatch");
    }

    @Then("a MarketVolumeTick SBE message is received on the channel")
    public void volumeMsgReceived() {
        assertTrue(messageReceived.get(), "Expected a MarketVolumeTick on the Aeron channel");
    }

    @And("the IEV field is {long}")
    public void ievIs(final long expected) {
        assertEquals(expected, capturedIev.get(), "IEV mismatch");
    }

    @And("the daily volume field is {long}")
    public void dailyVolumeIs(final long expected) {
        assertEquals(expected, capturedDailyVolume.get(), "Daily volume mismatch");
    }

    @Then("a ReferenceDataRecord SBE message is received on the channel")
    public void refDataMsgReceived() {
        assertTrue(messageReceived.get(), "Expected a ReferenceDataRecord on the Aeron channel");
    }

    @And("the lot size field is {long}")
    public void lotSizeIs(final long expected) {
        assertEquals(expected, capturedLotSize.get(), "Lot size mismatch");
    }

    @And("the tick size field is {long}")
    public void tickSizeIs(final long expected) {
        assertEquals(expected, capturedTickSize.get(), "Tick size mismatch");
    }

    @And("the constituent weight field is {long}")
    public void constituentWeightIs(final long expected) {
        assertEquals(expected, capturedConstituentWeight.get(), "Constituent weight mismatch");
    }

    // ── Private drain helpers ─────────────────────────────────────────────────

    private void drainQuote() {
        final long deadline = System.currentTimeMillis() + 2_000;
        while (!messageReceived.get() && System.currentTimeMillis() < deadline) {
            subscriber.poll((buffer, offset, length, header) -> {
                headerDecoder.wrap(buffer, offset);
                if (headerDecoder.templateId() != QuoteTickDecoder.TEMPLATE_ID) return;
                quoteDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    headerDecoder.blockLength(), headerDecoder.version());
                capturedIep.set(quoteDecoder.iep());
                capturedBid.set(quoteDecoder.bidPrice());
                capturedAsk.set(quoteDecoder.askPrice());
                capturedExchange.set(quoteDecoder.exchange().name());
                messageReceived.set(true);
            });
            Thread.onSpinWait();
        }
    }

    private void drainVolume() {
        final long deadline = System.currentTimeMillis() + 2_000;
        while (!messageReceived.get() && System.currentTimeMillis() < deadline) {
            subscriber.poll((buffer, offset, length, header) -> {
                headerDecoder.wrap(buffer, offset);
                if (headerDecoder.templateId() != MarketVolumeTickDecoder.TEMPLATE_ID) return;
                volDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    headerDecoder.blockLength(), headerDecoder.version());
                capturedIev.set(volDecoder.iev());
                capturedDailyVolume.set(volDecoder.dailyVolume());
                messageReceived.set(true);
            });
            Thread.onSpinWait();
        }
    }

    private void drainRefData() {
        final long deadline = System.currentTimeMillis() + 2_000;
        while (!messageReceived.get() && System.currentTimeMillis() < deadline) {
            subscriber.poll((buffer, offset, length, header) -> {
                headerDecoder.wrap(buffer, offset);
                if (headerDecoder.templateId() != ReferenceDataRecordDecoder.TEMPLATE_ID) return;
                refDataDecoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    headerDecoder.blockLength(), headerDecoder.version());
                capturedLotSize.set(refDataDecoder.lotSize());
                capturedTickSize.set(refDataDecoder.tickSize());
                capturedConstituentWeight.set(refDataDecoder.constituentWeight());
                messageReceived.set(true);
            });
            Thread.onSpinWait();
        }
    }
}
