package com.arb.marketdata.steps;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.marketdata.gateway.MarketDataGateway;
import com.arb.marketdata.handler.CsiFeedHandler;
import com.arb.marketdata.handler.HkexFeedHandler;
import com.arb.marketdata.handler.TaifexFeedHandler;
import com.arb.sbe.Exchange;
import com.arb.sbe.MarketDataTickDecoder;
import com.arb.sbe.MessageHeaderDecoder;
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

public class MarketDataSteps {

    private MediaDriver       driver;
    private Aeron             aeron;
    private MarketDataGateway gateway;
    private AeronSubscriber   subscriber;
    private Publication       publication;
    private Subscription      subscription;

    // Captured decoded fields from the received message
    private final AtomicBoolean           messageReceived = new AtomicBoolean(false);
    private final AtomicLong              capturedPrice   = new AtomicLong();
    private final AtomicReference<Exchange> capturedExchange = new AtomicReference<>();
    private final AtomicReference<String> capturedSymbol  = new AtomicReference<>();

    private final MessageHeaderDecoder    headerDecoder = new MessageHeaderDecoder();
    private final MarketDataTickDecoder   tickDecoder   = new MarketDataTickDecoder();

    @Before
    public void setUp() {
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);
        driver = MediaDriver.launchEmbedded(driverCtx);
        aeron  = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
    }

    @After
    public void tearDown() {
        if (gateway    != null) gateway.close();
        if (subscriber != null) subscriber.close();
        if (subscription != null && !subscription.isClosed()) subscription.close();
        if (publication  != null && !publication.isClosed())  publication.close();
        if (aeron  != null) aeron.close();
        if (driver != null) driver.close();
        messageReceived.set(false);
    }

    @Given("the MarketDataGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL")
    public void theGatewayIsRunning() {
        publication  = aeron.addPublication(Channels.CHANNEL, Channels.MARKET_DATA_STREAM);
        subscription = aeron.addSubscription(Channels.CHANNEL, Channels.MARKET_DATA_STREAM);

        while (!publication.isConnected()) {
            Thread.onSpinWait();
        }

        final AeronPublisher publisher = new AeronPublisher(publication);
        gateway    = new MarketDataGateway(publisher);
        subscriber = new AeronSubscriber(subscription);
    }

    @When("a tick arrives from HKEX for symbol {string} with price {double}")
    public void hkexTickArrives(final String symbol, final double price) {
        new HkexFeedHandler(gateway).onTick(symbol, price);
        drainAndCapture();
    }

    @When("a tick arrives from TAIFEX for symbol {string} with price {double}")
    public void taifexTickArrives(final String symbol, final double price) {
        new TaifexFeedHandler(gateway).onTick(symbol, price);
        drainAndCapture();
    }

    @When("a tick arrives from CSI for symbol {string} with price {double}")
    public void csiTickArrives(final String symbol, final double price) {
        new CsiFeedHandler(gateway).onTick(symbol, price);
        drainAndCapture();
    }

    @Then("a MarketDataTick SBE message is received on the channel")
    public void messageIsReceived() {
        assertTrue(messageReceived.get(), "Expected a MarketDataTick on the Aeron channel");
    }

    @And("the normalized price is {long}")
    public void normalizedPriceIs(final long expectedPrice) {
        assertEquals(expectedPrice, capturedPrice.get(),
            "Normalized price mismatch");
    }

    @And("the exchange field is {string}")
    public void exchangeFieldIs(final String expectedExchange) {
        assertEquals(expectedExchange, capturedExchange.get().name(),
            "Exchange field mismatch");
    }

    @And("the symbol field is {string}")
    public void symbolFieldIs(final String expectedSymbol) {
        assertEquals(expectedSymbol, capturedSymbol.get().trim(),
            "Symbol field mismatch");
    }

    // --- helpers ---

    private void drainAndCapture() {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!messageReceived.get() && System.currentTimeMillis() < deadline) {
            subscriber.poll((buffer, offset, length, header) -> {
                headerDecoder.wrap(buffer, offset);

                tickDecoder.wrap(
                    buffer,
                    offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    headerDecoder.blockLength(),
                    headerDecoder.version()
                );

                capturedPrice.set(tickDecoder.price());
                capturedExchange.set(tickDecoder.exchange());

                final byte[] symbolBytes = new byte[MarketDataTickDecoder.symbolLength()];
                tickDecoder.getSymbol(symbolBytes, 0);
                capturedSymbol.set(new String(symbolBytes).trim());

                messageReceived.set(true);
            });
            Thread.onSpinWait();
        }
    }
}
