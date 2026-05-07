package com.arb.marketdata.replay;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Replays a pre-recorded scenario by publishing SBE messages to Aeron streams.
 *
 * <h3>Timing</h3>
 * Frame timestamps are relative to scenario start. The engine sleeps between frames
 * to maintain the scenario's original cadence, scaled by {@code speedMultiplier}
 * (1.0 = real-time, 2.0 = 2× faster, 0.5 = half-speed).
 */
public final class ReplayEngine {

    private static final int FV_MSG_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + FvUpdateEncoder.BLOCK_LENGTH;
    private static final int MD_MSG_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + MarketDataTickEncoder.BLOCK_LENGTH;

    private final AeronPublisher mdPublisher;
    private final AeronPublisher fvPublisher;
    private final double         speedMultiplier;

    // Pre-allocated SBE flyweights — zero-GC
    private final UnsafeBuffer          txBuf      = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final MessageHeaderEncoder  hdrEncoder = new MessageHeaderEncoder();
    private final MarketDataTickEncoder mdEncoder  = new MarketDataTickEncoder();
    private final FvUpdateEncoder       fvEncoder  = new FvUpdateEncoder();

    public ReplayEngine(final AeronPublisher mdPublisher,
                        final AeronPublisher fvPublisher,
                        final double speedMultiplier) {
        this.mdPublisher     = mdPublisher;
        this.fvPublisher     = fvPublisher;
        this.speedMultiplier = speedMultiplier;
    }

    /**
     * Replay all frames from a classpath scenario resource.
     * Blocks until all frames are published or the thread is interrupted.
     */
    public void replayClasspath(final String resourcePath) throws IOException, InterruptedException {
        replay(ScenarioLoader.loadFromClasspath(resourcePath));
    }

    /**
     * Replay a list of pre-loaded frames.
     */
    public void replay(final List<ScenarioFrame> frames) throws InterruptedException {
        if (frames.isEmpty()) return;

        System.out.printf("[replay] Starting replay of %d frames at %.1fx speed%n",
            frames.size(), speedMultiplier);

        final long wallStart     = System.currentTimeMillis();
        final long scenarioStart = frames.get(0).tsMillis();

        for (final ScenarioFrame frame : frames) {
            final long scenarioElapsed = frame.tsMillis() - scenarioStart;
            final long targetWall      = wallStart + (long) (scenarioElapsed / speedMultiplier);
            final long sleepMs         = targetWall - System.currentTimeMillis();
            if (sleepMs > 0) Thread.sleep(sleepMs);

            if ("MD".equals(frame.type())) {
                publishMd(frame);
            } else {
                publishFv(frame);
            }
        }

        System.out.println("[replay] Scenario replay complete.");
    }

    /** Total duration of the scenario in milliseconds. */
    public static long scenarioDurationMs(final List<ScenarioFrame> frames) {
        if (frames.isEmpty()) return 0;
        return frames.get(frames.size() - 1).tsMillis() - frames.get(0).tsMillis();
    }

    private void publishMd(final ScenarioFrame f) {
        final Exchange exchange = Exchange.valueOf(f.exchange());
        mdEncoder.wrapAndApplyHeader(txBuf, 0, hdrEncoder)
            .symbol(f.symbol())
            .exchange(exchange)
            .price(f.price())
            .qty(f.qty())
            .timestamp(System.nanoTime());
        mdPublisher.publish(txBuf, 0, MD_MSG_LENGTH);
    }

    private void publishFv(final ScenarioFrame f) {
        final Exchange exchange = Exchange.valueOf(f.exchange());
        fvEncoder.wrapAndApplyHeader(txBuf, 0, hdrEncoder)
            .symbol(f.symbol())
            .exchange(exchange)
            .navPerUnit(f.navPerUnit())
            .futuresFv(f.futuresFv())
            .basis(f.basis())
            .annualisedBasisBps(f.annualisedBasisBps100())
            .timestamp(System.nanoTime());
        fvPublisher.publish(txBuf, 0, FV_MSG_LENGTH);
    }
}
