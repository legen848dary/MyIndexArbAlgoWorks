package com.arb.common.metrics;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes latency histogram snapshots to LATENCY_STREAM (1006) every N seconds.
 * Runs on a dedicated reporter thread — NOT on the hot path.
 */
public final class LatencyPublisher implements AutoCloseable {

    private static final int MSG_LEN =
        MessageHeaderEncoder.ENCODED_LENGTH + LatencyStatsEncoder.BLOCK_LENGTH;

    private record Entry(String category, LatencyRecorder recorder) {}

    private final AeronPublisher publisher;
    private final List<Entry>    entries = new ArrayList<>();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "latency-reporter");
            t.setDaemon(true);
            return t;
        });

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final LatencyStatsEncoder  statsEncoder  = new LatencyStatsEncoder();
    private final UnsafeBuffer         txBuf         =
        new UnsafeBuffer(ByteBuffer.allocateDirect(MSG_LEN + 16));
    private final byte[]               catBuf        = new byte[8];

    public LatencyPublisher(final AeronPublisher publisher) {
        this.publisher = publisher;
    }

    /** Register a recorder + 8-char category label (e.g. "SIGNAL\0\0"). */
    public void register(final String category, final LatencyRecorder recorder) {
        entries.add(new Entry(category, recorder));
    }

    /** Start publishing snapshots every intervalSecs seconds. */
    public void start(final int intervalSecs) {
        scheduler.scheduleAtFixedRate(this::publish, intervalSecs, intervalSecs, TimeUnit.SECONDS);
    }

    private void publish() {
        for (final Entry e : entries) {
            final LatencyRecorder rec = e.recorder();
            final long n = rec.sampleCount();
            if (n == 0) continue;

            final long[] counts = rec.countsSnapshot();
            final long minNs    = rec.minNs();
            final long maxNs    = rec.maxNs();
            final long avgNs    = rec.avgNs();

            final byte[] catBytes = e.category().getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 8; i++) {
                catBuf[i] = i < catBytes.length ? catBytes[i] : 0;
            }

            statsEncoder.wrapAndApplyHeader(txBuf, 0, headerEncoder)
                .putCategory(catBuf, 0)
                .b0Sub1us   (counts[0])
                .b1to5us    (counts[1])
                .b5to10us   (counts[2])
                .b10to50us  (counts[3])
                .b50to100us (counts[4])
                .b100to500us(counts[5])
                .bOver500us (counts[6])
                .minNs      (minNs)
                .maxNs      (maxNs)
                .avgNs      (avgNs)
                .sampleCount(n)
                .timestamp  (System.currentTimeMillis());

            publisher.publish(txBuf, 0, MSG_LEN);
            rec.reset();
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
