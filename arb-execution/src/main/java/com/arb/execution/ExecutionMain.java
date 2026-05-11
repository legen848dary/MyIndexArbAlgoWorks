package com.arb.execution;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import io.aeron.Aeron;

/**
 * Entry point for the arb-execution service.
 * Connects to the shared Aeron MediaDriver as a client (no embedded driver).
 * Wires RiskGateway + MockExchangeConnector and runs the poll loop.
 */
public final class ExecutionMain {

    private static final String AERON_DIR = "/dev/shm/aeron";

    // MockExchangeConnector simulated round-trip latency: 10–50 µs
    private static final long MIN_LATENCY_NS = 10_000L;
    private static final long MAX_LATENCY_NS = 50_000L;

    public static void main(final String[] args) {
        // 1. Connect as Aeron client — MediaDriver is owned by arb-market-data
        final Aeron aeron = Aeron.connect(
            new Aeron.Context().aeronDirectoryName(AERON_DIR));
        System.out.println("[execution] Aeron client connected to " + AERON_DIR);

        // 2. Subscriber for ORDER_STREAM, publisher for ORDER_UPDATE_STREAM
        final AeronSubscriber orderSub = new AeronSubscriber(
            aeron.addSubscription(Channels.CHANNEL, Channels.ORDER_STREAM));
        final AeronPublisher orderUpdatePub = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.ORDER_UPDATE_STREAM));

        // 3. Wire execution components
        final RiskConfig             riskConfig = RiskConfig.defaultConfig();
        final PositionBook           positions  = new PositionBook(64);
        final MockExchangeConnector  connector  = new MockExchangeConnector(
            orderUpdatePub, MIN_LATENCY_NS, MAX_LATENCY_NS);
        final RiskGateway            riskGateway = new RiskGateway(riskConfig, positions, connector);

        final AeronPublisher latencyPub = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.LATENCY_STREAM));
        final com.arb.common.metrics.LatencyPublisher latencyPublisher =
            new com.arb.common.metrics.LatencyPublisher(latencyPub);
        latencyPublisher.register("RISK_CHK", riskGateway.riskCheckLatencyRecorder());
        latencyPublisher.start(5);

        // 4. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            System.out.println("[execution] Shutting down...");
            latencyPublisher.close();
            connector.close();
            aeron.close();
        }));

        // 5. Blocking poll loop
        System.out.println("[execution] RiskGateway poll loop starting...");
        while (!Thread.currentThread().isInterrupted()) {
            orderSub.poll(riskGateway::onFragment);
            connector.drainPending();
        }
    }
}
