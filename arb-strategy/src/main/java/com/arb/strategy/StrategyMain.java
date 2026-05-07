package com.arb.strategy;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.sbe.MarketDataTickDecoder;
import com.arb.sbe.OrderRequestEncoder;
import com.arb.sbe.QuoteTickDecoder;
import com.arb.strategy.sequencer.ArbSequencer;
import io.aeron.Aeron;

import java.util.List;

/**
 * Entry point for the arb-strategy service.
 * Connects to the shared Aeron MediaDriver as a client (no embedded driver).
 * Wires StrategyRegistry → ArbSequencer and runs the busy-poll event loop.
 */
public final class StrategyMain {

    private static final String AERON_DIR = "/dev/shm/aeron";

    public static void main(final String[] args) {
        // 1. Connect as Aeron client — MediaDriver is owned by arb-market-data
        final Aeron aeron = Aeron.connect(
            new Aeron.Context().aeronDirectoryName(AERON_DIR));
        System.out.println("[strategy] Aeron client connected to " + AERON_DIR);

        // 2. Subscribers and publisher
        final AeronSubscriber marketDataSub = new AeronSubscriber(
            aeron.addSubscription(Channels.CHANNEL, Channels.MARKET_DATA_STREAM));
        final AeronPublisher orderPublisher = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.ORDER_STREAM));

        // 3. Load strategy registry from classpath config/strategies.properties
        final StrategyRegistry registry = new StrategyRegistry("config/strategies.properties");
        final List<Strategy> strategies = registry.enabledStrategies();

        final Strategy activeStrategy;
        if (strategies.isEmpty()) {
            System.out.println("[strategy] No strategies enabled — using no-op fallback");
            activeStrategy = new NoOpStrategy();
        } else {
            activeStrategy = strategies.get(0);
            System.out.println("[strategy] Active strategy: " + activeStrategy.getClass().getSimpleName());
        }

        // 4. Wire ArbSequencer
        final ArbSequencer sequencer = new ArbSequencer(marketDataSub, orderPublisher, activeStrategy);

        // 5. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            System.out.println("[strategy] Shutting down...");
            sequencer.stop();
            aeron.close();
        }));

        // 6. Blocking busy-poll loop
        System.out.println("[strategy] ArbSequencer starting...");
        sequencer.start();
    }

    /** No-op strategy used as fallback when no strategies are configured. */
    private static final class NoOpStrategy implements Strategy {
        @Override
        public void onMarketData(final com.arb.sbe.MarketDataTickDecoder tick,
                                  final OrderSink orders) {}

        @Override
        public void onTimer(final long nowNanos, final OrderSink orders) {}
    }
}
