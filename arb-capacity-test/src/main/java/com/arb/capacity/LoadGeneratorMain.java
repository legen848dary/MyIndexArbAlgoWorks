package com.arb.capacity;

import com.arb.common.Channels;
import com.arb.sbe.*;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class LoadGeneratorMain {

    private static final int FRAGMENT_LIMIT = 100;
    private static final int HDR_LEN        = MessageHeaderEncoder.ENCODED_LENGTH;
    private static final int SYM_LEN        = FvUpdateEncoder.symbolLength();
    private static final int FV_MSG_LEN     = HDR_LEN + FvUpdateEncoder.BLOCK_LENGTH;

    private static final byte[] HSI_SYM;
    static {
        HSI_SYM = new byte[SYM_LEN];
        byte[] raw = "HSI.HK".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, HSI_SYM, 0, raw.length);
    }

    private static final long[] BOUNDS_US = {1, 5, 10, 50, 100, 500, 1_000};
    private static final int BUCKETS = BOUNDS_US.length + 1;

    public static void main(final String[] args) throws Exception {
        String mode        = "flood";
        int    durationSec = 30;
        int    burstCount  = 50_000;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode"     -> mode = args[++i];
                case "--duration" -> durationSec = Integer.parseInt(args[++i]);
                case "--count"    -> burstCount = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Usage: --mode flood|burst [--duration N] [--count N]");
                    System.exit(1);
                }
            }
        }

        printBanner(mode, durationSec, burstCount);

        try (Aeron aeron = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName("/dev/shm/aeron"))) {

            System.out.println("  Connecting to Aeron IPC fabric (/dev/shm/aeron) ...");

            if ("flood".equals(mode)) {
                runFlood(aeron, durationSec);
            } else {
                runBurst(aeron, burstCount);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Flood: publish FvUpdates at max rate, count OrderRequests
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runFlood(final Aeron aeron, final int durationSec) throws Exception {
        System.out.println("  [FLOOD] Publishing FvUpdate on stream 1005 for " + durationSec + "s ...");
        System.out.println("          Monitoring OrderRequests on stream 1002 ...\n");

        final AtomicLong      fvSent   = new AtomicLong(0);
        final AtomicLong      ordRecv  = new AtomicLong(0);
        final AtomicBoolean   done     = new AtomicBoolean(false);

        try (Publication fvPub = aeron.addPublication(Channels.CHANNEL, Channels.FV_STREAM);
             Subscription ordSub = aeron.addSubscription(Channels.CHANNEL, Channels.ORDER_STREAM)) {

            while (!fvPub.isConnected() || !ordSub.isConnected()) {
                Thread.sleep(50);
            }

            // ── Producer: publish FvUpdate on 1005 ──────────────────────────
            final Thread producer = new Thread(() -> {
                final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
                final MessageHeaderEncoder hEnc = new MessageHeaderEncoder();
                final FvUpdateEncoder enc = new FvUpdateEncoder();
                while (!done.get()) {
                    final long ts = System.nanoTime();
                    enc.wrapAndApplyHeader(buf, 0, hEnc)
                        .putSymbol(HSI_SYM, 0)
                        .exchange(Exchange.HKEX)
                        .navPerUnit(0L)
                        .futuresFv(190_000_000L)
                        .basis(500_000L)
                        .annualisedBasisBps(6000L)
                        .timestamp(ts);
                    long result;
                    do {
                        result = fvPub.offer(buf, 0, FV_MSG_LEN);
                    } while (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION);
                    if (result >= 0) fvSent.incrementAndGet();
                }
            }, "flood-producer");
            producer.setDaemon(true);

            // ── Consumer: count OrderRequests on 1002 ────────────────────────
            final MessageHeaderDecoder hDec = new MessageHeaderDecoder();
            final FragmentHandler ordHandler =
                (DirectBuffer buf, int off, int len, Header hdr) -> {
                    hDec.wrap(buf, off);
                    if (hDec.templateId() == OrderRequestDecoder.TEMPLATE_ID) {
                        ordRecv.incrementAndGet();
                    }
                };
            final Thread ordConsumer = new Thread(() -> {
                while (!done.get()) {
                    ordSub.poll(ordHandler, FRAGMENT_LIMIT);
                }
                for (int i = 0; i < 50; i++) {
                    if (ordSub.poll(ordHandler, FRAGMENT_LIMIT) == 0) break;
                }
            }, "flood-ord-consumer");
            ordConsumer.setDaemon(true);

            producer.start();
            ordConsumer.start();

            // Warmup
            System.out.println("  Warming up (3s) ...");
            Thread.sleep(3_000);
            fvSent.set(0); ordRecv.set(0);

            // Measurement
            System.out.println("  Measuring (" + durationSec + "s) ...");
            final long t0 = System.nanoTime();
            Thread.sleep(durationSec * 1000L);
            final long t1 = System.nanoTime();
            done.set(true);

            producer.join(2000);
            ordConsumer.join(2000);

            final double elapsedSec = (t1 - t0) / 1_000_000_000.0;

            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
            System.out.println("  ║                 FLOOD MODE RESULTS (Live System)                ║");
            System.out.println("  ╠══════════════════════════════════════════════════════════════════╣");
            System.out.printf ("  ║ FvUpdates sent:    %,12.0f msgs/sec                          ║%n", fvSent.get() / elapsedSec);
            System.out.printf ("  ║ OrderRequests recv: %,12.0f msgs/sec                          ║%n", ordRecv.get() / elapsedSec);
            System.out.printf ("  ║ Conversion rate:   %,12.1f %% (how many FV → Orders)          ║%n",
                fvSent.get() > 0 ? ordRecv.get() * 100.0 / fvSent.get() : 0);
            System.out.printf ("  ║ Total FV sent:     %,12d                                      ║%n", fvSent.get());
            System.out.printf ("  ║ Total Orders recv: %,12d                                      ║%n", ordRecv.get());
            System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Burst: publish N FvUpdates with trace timestamps, measure e2e order latency
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runBurst(final Aeron aeron, final int count) throws Exception {
        System.out.println("  [BURST] Sending " + count + " FvUpdate messages, tracing OrderRequests ...\n");

        final AtomicLong    ordRecv = new AtomicLong(0);
        final AtomicBoolean done    = new AtomicBoolean(false);
        final Histogram     histo   = new Histogram();

        try (Publication fvPub = aeron.addPublication(Channels.CHANNEL, Channels.FV_STREAM);
             Subscription ordSub = aeron.addSubscription(Channels.CHANNEL, Channels.ORDER_STREAM)) {

            while (!fvPub.isConnected() || !ordSub.isConnected()) {
                Thread.sleep(50);
            }

            // ── Order consumer: record when OrderRequests arrive ─────────────
            final MessageHeaderDecoder hDec = new MessageHeaderDecoder();
            final OrderRequestDecoder ordDec = new OrderRequestDecoder();
            final long ordStartNs = System.nanoTime();

            final Thread ordConsumer = new Thread(() -> {
                while (!done.get()) {
                    ordSub.poll((DirectBuffer buf, int off, int len, Header hdr) -> {
                        hDec.wrap(buf, off);
                        if (hDec.templateId() == OrderRequestDecoder.TEMPLATE_ID) {
                            ordDec.wrap(buf, off + HDR_LEN, hDec.blockLength(), hDec.version());
                            ordRecv.incrementAndGet();
                        }
                    }, FRAGMENT_LIMIT);
                }
                ordSub.poll((DirectBuffer buf, int off, int len, Header hdr) -> {
                    hDec.wrap(buf, off);
                    if (hDec.templateId() == OrderRequestDecoder.TEMPLATE_ID) {
                        ordDec.wrap(buf, off + HDR_LEN, hDec.blockLength(), hDec.version());
                        ordRecv.incrementAndGet();
                    }
                }, FRAGMENT_LIMIT);
            }, "burst-ord-consumer");
            ordConsumer.setDaemon(true);
            ordConsumer.start();

            // ── Publish burst of FvUpdates ───────────────────────────────────
            final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
            final MessageHeaderEncoder hEnc = new MessageHeaderEncoder();
            final FvUpdateEncoder enc = new FvUpdateEncoder();

            final long t0 = System.nanoTime();

            for (int i = 0; i < count; i++) {
                final long ts = System.nanoTime();
                enc.wrapAndApplyHeader(buf, 0, hEnc)
                    .putSymbol(HSI_SYM, 0)
                    .exchange(Exchange.HKEX)
                    .navPerUnit(0L)
                    .futuresFv(190_000_000L)
                    .basis(500_000L)
                    .annualisedBasisBps(6000L)
                    .timestamp(ts);

                long result;
                do {
                    result = fvPub.offer(buf, 0, FV_MSG_LEN);
                } while (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION);

                if (i % 50000 == 0 && i > 0) {
                    System.out.printf("    published %,d / %,d (%.0f%%) ...%n",
                        i, count, i * 100.0 / count);
                }
            }

            final long t1 = System.nanoTime();
            final double burstSec = (t1 - t0) / 1_000_000_000.0;

            // Drain
            System.out.println("  Draining pipeline (max 10s) ...");
            long drainDeadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < drainDeadline) {
                long current = ordRecv.get();
                Thread.sleep(500);
                if (ordRecv.get() == current && ordRecv.get() > 0) break; // stabilized
            }
            done.set(true);
            ordConsumer.join(3000);

            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
            System.out.println("  ║                 BURST MODE RESULTS (Live System)                ║");
            System.out.println("  ╠══════════════════════════════════════════════════════════════════╣");
            System.out.printf ("  ║ Burst size:       %,12d FvUpdates                             ║%n", count);
            System.out.printf ("  ║ Publish rate:     %,12.0f msgs/sec                            ║%n", count / burstSec);
            System.out.printf ("  ║ Publish duration: %,12.1f ms                                   ║%n", burstSec * 1000);
            System.out.printf ("  ║ Orders received:  %,12d                                       ║%n", ordRecv.get());
            System.out.printf ("  ║ Signal rate:      %,12.1f%% (orders / published)               ║%n",
                ordRecv.get() * 100.0 / count);
            System.out.println("  ╠══════════════════════════════════════════════════════════════════╣");
            System.out.println("  ║ Note: strategy has 25s cooldown; signals are design-limited.     ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private static void printBanner(final String mode, final int dur, final int count) {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║   myIndexArbAlgoWorks — Load Generator (Phase B)            ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════╣");
        System.out.printf ("  ║ Mode: %-10s   Duration: %-5s   Count: %-8s       ║%n",
            mode, dur + "s", count);
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Histogram
    // ═══════════════════════════════════════════════════════════════════════════

    private static final class Histogram {
        private final long[] counts = new long[BUCKETS];
        private long total = 0;
        private long maxNs = 0;

        void record(final long nanos) {
            if (nanos < 0) return;
            total++;
            if (nanos > maxNs) maxNs = nanos;
            final long us = nanos / 1_000;
            for (int i = 0; i < BOUNDS_US.length; i++) {
                if (us < BOUNDS_US[i]) { counts[i]++; return; }
            }
            counts[BUCKETS - 1]++;
        }

        void reset() { Arrays.fill(counts, 0L); total = 0; maxNs = 0; }

        double maxUs() { return maxNs / 1_000.0; }
    }
}
