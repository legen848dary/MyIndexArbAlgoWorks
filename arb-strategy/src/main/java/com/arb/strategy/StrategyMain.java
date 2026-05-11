package com.arb.strategy;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.sbe.*;
import com.arb.strategy.sequencer.ArbSequencer;
import io.aeron.Aeron;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for the arb-strategy service.
 *
 * <p>Subscribes to MARKET_DATA_STREAM, FV_STREAM, and CONTROL_STREAM.
 * Runs ALL enabled strategies via {@link MultiStrategy} so the dashboard can
 * toggle individual strategies on/off at runtime via {@code START_STRATEGY:Name}
 * / {@code STOP_STRATEGY:Name} commands on CONTROL_STREAM.
 */
public final class StrategyMain {

    private static final String AERON_DIR = "/dev/shm/aeron";

    public static void main(final String[] args) {
        // 1. Connect as Aeron client — MediaDriver is owned by arb-market-data
        final Aeron aeron = Aeron.connect(
            new Aeron.Context().aeronDirectoryName(AERON_DIR));
        System.out.println("[strategy] Aeron client connected to " + AERON_DIR);

        // 2. Subscriptions: MARKET_DATA, FV (critical — FvUpdate triggers arb logic), CONTROL
        final AeronSubscriber marketDataSub = new AeronSubscriber(
            aeron.addSubscription(Channels.CHANNEL, Channels.MARKET_DATA_STREAM));
        final AeronSubscriber fvSub = new AeronSubscriber(
            aeron.addSubscription(Channels.CHANNEL, Channels.FV_STREAM));
        final AeronSubscriber controlSub = new AeronSubscriber(
            aeron.addSubscription(Channels.CHANNEL, Channels.CONTROL_STREAM));
        final AeronPublisher orderPublisher = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.ORDER_STREAM));
        final AeronPublisher latencyPub = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.LATENCY_STREAM));
        final com.arb.common.metrics.LatencyPublisher latencyPublisher =
            new com.arb.common.metrics.LatencyPublisher(latencyPub);

        // 3. Load strategy registry — all enabled strategies run in parallel
        final StrategyRegistry registry = new StrategyRegistry("config/strategies.properties");
        final List<Strategy> strategies = registry.enabledStrategies();

        final MultiStrategy multiStrategy;
        if (strategies.isEmpty()) {
            System.out.println("[strategy] WARNING: no strategies enabled — check config/strategies.properties");
            multiStrategy = new MultiStrategy(List.of(new NoOpStrategy("NoOp")));
        } else {
            multiStrategy = new MultiStrategy(strategies);
            strategies.forEach(s -> System.out.printf("[strategy] Loaded: %s%n", s.getClass().getSimpleName()));
        }

        // 4. Wire ArbSequencer — extra subscribers for FV and CONTROL streams
        final ArbSequencer sequencer = new ArbSequencer(marketDataSub, orderPublisher, multiStrategy);
        sequencer.addSubscriber(fvSub);
        sequencer.addSubscriber(controlSub);

        // 5. Wire dashboard strategy toggle commands to MultiStrategy
        sequencer.setCommandHandler(cmd -> {
            if (cmd.startsWith("START_STRATEGY:")) {
                multiStrategy.setEnabled(cmd.substring("START_STRATEGY:".length()).trim(), true);
            } else if (cmd.startsWith("STOP_STRATEGY:")) {
                multiStrategy.setEnabled(cmd.substring("STOP_STRATEGY:".length()).trim(), false);
            }
        });

        // Register latency recorders for enabled strategies
        for (final Strategy s : strategies) {
            if (s instanceof com.arb.strategy.impl.HkexBasisArb hkex) {
                latencyPublisher.register("SIGNAL\0\0", hkex.signalLatencyRecorder());
            }
        }
        latencyPublisher.start(5);

        // 6. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            System.out.println("[strategy] Shutting down...");
            sequencer.stop();
            latencyPublisher.close();
            aeron.close();
        }));

        // 7. Blocking busy-poll loop
        System.out.println("[strategy] ArbSequencer starting — polling MARKET_DATA, FV, CONTROL streams...");
        sequencer.start();
    }

    // ── MultiStrategy: dispatches to all enabled strategies ──────────────────

    static final class MultiStrategy implements Strategy {

        private record Entry(String name, Strategy strategy, AtomicBoolean enabled) {}

        private final List<Entry> entries;

        MultiStrategy(final List<Strategy> strategies) {
            this.entries = strategies.stream()
                .map(s -> new Entry(s.getClass().getSimpleName(), s, new AtomicBoolean(true)))
                .toList();
        }

        void setEnabled(final String name, final boolean enabled) {
            boolean found = false;
            for (final Entry e : entries) {
                if (e.name().equals(name)) {
                    e.enabled().set(enabled);
                    System.out.printf("[strategy] %s %s%n", name, enabled ? "ENABLED" : "DISABLED");
                    found = true;
                }
            }
            if (!found) System.out.printf("[strategy] WARNING: unknown strategy '%s'%n", name);
        }

        @Override
        public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {
            for (final Entry e : entries) if (e.enabled().get()) e.strategy().onMarketData(tick, orders);
        }

        @Override
        public void onFvUpdate(final FvUpdateDecoder fv, final OrderSink orders) {
            for (final Entry e : entries) if (e.enabled().get()) e.strategy().onFvUpdate(fv, orders);
        }

        @Override
        public void onTimer(final long nowNanos, final OrderSink orders) {
            for (final Entry e : entries) if (e.enabled().get()) e.strategy().onTimer(nowNanos, orders);
        }

        @Override
        public void onQuote(final QuoteTickDecoder tick, final OrderSink orders) {
            for (final Entry e : entries) if (e.enabled().get()) e.strategy().onQuote(tick, orders);
        }

        @Override
        public void onMarketVolume(final MarketVolumeTickDecoder tick, final OrderSink orders) {
            for (final Entry e : entries) if (e.enabled().get()) e.strategy().onMarketVolume(tick, orders);
        }

        @Override
        public void onReferenceData(final ReferenceDataRecordDecoder record) {
            for (final Entry e : entries) e.strategy().onReferenceData(record);
        }
    }

    // ── No-op fallback ───────────────────────────────────────────────────────

    private static final class NoOpStrategy implements Strategy {
        private final String name;
        NoOpStrategy(final String name) { this.name = name; }

        @Override
        public void onMarketData(final MarketDataTickDecoder tick, final OrderSink orders) {}

        @Override
        public void onTimer(final long nowNanos, final OrderSink orders) {}

        @Override
        public String toString() { return name; }
    }
}
