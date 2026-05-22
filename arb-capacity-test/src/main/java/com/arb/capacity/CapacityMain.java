package com.arb.capacity;

import com.arb.gambit.realtime.FuturesFvCalculator;
import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.impl.HkexBasisArb;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CapacityMain {

    private static final int WARMUP_SECS   = 1;
    private static final int MEASURE_SECS  = 5;
    private static final int FRAGMENT_LIMIT = 100;
    private static final String CHANNEL     = "aeron:ipc";

    private static final int RISK_FREE_BPS = 250;
    private static final int DAYS_TO_EXP   = 30;

    private static final int SYM_LEN = MarketDataTickEncoder.symbolLength();
    private static final byte[] HSI_SYM;
    static {
        HSI_SYM = new byte[SYM_LEN];
        byte[] raw = "HSI.HK".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, HSI_SYM, 0, raw.length);
    }

    private static final int MD_MSG_LEN =
        MessageHeaderEncoder.ENCODED_LENGTH + MarketDataTickEncoder.BLOCK_LENGTH;
    private static final int FV_MSG_LEN =
        MessageHeaderEncoder.ENCODED_LENGTH + FvUpdateEncoder.BLOCK_LENGTH;
    private static final int HDR_LEN = MessageHeaderEncoder.ENCODED_LENGTH;

    private static final int STREAM_A  = 9001;
    private static final int STREAM_B  = 9002;
    private static final int STREAM_C  = 9003;
    private static final int STREAM_CO = 9004;
    private static final int STREAM_E  = 9005;

    private static final PrintStream NULL_STREAM = new PrintStream(OutputStream.nullOutputStream());
    private static final PrintStream REAL_OUT    = System.out;

    public static void main(final String[] args) throws Exception {
        printBanner();
        System.out.println("  warmup=" + WARMUP_SECS + "s  measure=" + MEASURE_SECS + "s per scenario\n");

        Result ra = scenarioRawIpc();
        System.out.println();
        Result rb = scenarioSbeMdTick();
        System.out.println();
        Result rc = scenarioFvEngine();
        System.out.println();
        Result rd = scenarioStrategyEval();
        System.out.println();
        Result re = scenarioFullPipeline();

        printSummary(new Result[]{ra, rb, rc, rd, re});
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  A: Raw IPC — throughput only (no compute to measure)
    // ═══════════════════════════════════════════════════════════════════════════
    private static Result scenarioRawIpc() {
        System.out.println("  [A] Raw Aeron IPC ...");
        final MediaDriver driver = launchDriver();
        final Aeron aeron = connectAeron(driver);
        try {
            final AtomicLong recv = new AtomicLong(0);
            final AtomicBoolean done = new AtomicBoolean(false);

            try (Publication pub = aeron.addPublication(CHANNEL, STREAM_A);
                 Subscription sub = aeron.addSubscription(CHANNEL, STREAM_A)) {

                while (!pub.isConnected()) Thread.onSpinWait();

                final UnsafeBuffer txBuf = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
                final int payloadLen = 8;

                final Thread producer = new Thread(() -> {
                    while (!done.get()) {
                        txBuf.putLong(0, 0L, java.nio.ByteOrder.LITTLE_ENDIAN);
                        pub.offer(txBuf, 0, payloadLen);
                    }
                }, "producer-A");
                producer.setDaemon(true);

                final FragmentHandler handler =
                    (buf, off, len, hdr) -> recv.incrementAndGet();

                final Thread consumer = new Thread(() -> {
                    while (!done.get()) {
                        sub.poll(handler, FRAGMENT_LIMIT);
                    }
                    drain(sub, handler);
                }, "consumer-A");
                consumer.setDaemon(true);

                producer.start();
                consumer.start();

                sleepSeconds(WARMUP_SECS);
                recv.set(0);

                sleepSeconds(MEASURE_SECS);
                done.set(true);

                producer.join(2000);
                consumer.join(2000);
            }
            return new Result("A: Raw Aeron IPC", recv.get() / (double) MEASURE_SECS,
                Double.NaN, Double.NaN, Double.NaN);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            aeron.close();
            driver.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  B: SBE MarketDataTick — measure decode processing time
    // ═══════════════════════════════════════════════════════════════════════════
    private static Result scenarioSbeMdTick() {
        System.out.println("  [B] SBE MarketDataTick encode/decode ...");
        final MediaDriver driver = launchDriver();
        final Aeron aeron = connectAeron(driver);
        try {
            final AtomicLong recv = new AtomicLong(0);
            final AtomicBoolean done = new AtomicBoolean(false);
            final Histogram histo = new Histogram();

            try (Publication pub = aeron.addPublication(CHANNEL, STREAM_B);
                 Subscription sub = aeron.addSubscription(CHANNEL, STREAM_B)) {

                while (!pub.isConnected()) Thread.onSpinWait();

                final MessageHeaderDecoder hdrDec = new MessageHeaderDecoder();
                final MarketDataTickDecoder mdDec = new MarketDataTickDecoder();

                final Thread producer = new Thread(() -> {
                    final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
                    final MessageHeaderEncoder hEnc = new MessageHeaderEncoder();
                    final MarketDataTickEncoder enc = new MarketDataTickEncoder();
                    while (!done.get()) {
                        enc.wrapAndApplyHeader(buf, 0, hEnc)
                            .putSymbol(HSI_SYM, 0)
                            .exchange(Exchange.HKEX)
                            .price(190_000_000L)
                            .qty(100L)
                            .timestamp(System.nanoTime());
                        pub.offer(buf, 0, MD_MSG_LEN);
                    }
                }, "producer-B");
                producer.setDaemon(true);

                final FragmentHandler handler =
                    (DirectBuffer buf, int off, int len, Header hdr) -> {
                        final long t0 = System.nanoTime();
                        hdrDec.wrap(buf, off);
                        if (hdrDec.templateId() == MarketDataTickDecoder.TEMPLATE_ID) {
                            mdDec.wrap(buf, off + HDR_LEN, hdrDec.blockLength(), hdrDec.version());
                        }
                        histo.record(System.nanoTime() - t0);
                        recv.incrementAndGet();
                    };

                final Thread consumer = new Thread(() -> {
                    while (!done.get()) {
                        sub.poll(handler, FRAGMENT_LIMIT);
                    }
                    drain(sub, handler);
                }, "consumer-B");
                consumer.setDaemon(true);

                producer.start();
                consumer.start();

                sleepSeconds(WARMUP_SECS);
                recv.set(0); histo.reset();

                sleepSeconds(MEASURE_SECS);
                done.set(true);

                producer.join(2000);
                consumer.join(2000);
            }
            return buildResult("B: SBE MD Tick", recv.get(), MEASURE_SECS, histo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            aeron.close();
            driver.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  C: FvEngine Hot Path
    // ═══════════════════════════════════════════════════════════════════════════
    private static Result scenarioFvEngine() {
        System.out.println("  [C] FvEngine Hot Path (MD -> FV) ...");
        final MediaDriver driver = launchDriver();
        final Aeron aeron = connectAeron(driver);
        try {
            final AtomicLong recv = new AtomicLong(0);
            final AtomicBoolean done = new AtomicBoolean(false);
            final Histogram histo = new Histogram();
            final AtomicLong divPv = new AtomicLong(0);

            try (Publication pubIn = aeron.addPublication(CHANNEL, STREAM_C);
                 Subscription subIn = aeron.addSubscription(CHANNEL, STREAM_C);
                 Publication pubOut = aeron.addPublication(CHANNEL, STREAM_CO)) {

                while (!pubIn.isConnected()) Thread.onSpinWait();

                final MessageHeaderDecoder hdrDec = new MessageHeaderDecoder();
                final MarketDataTickDecoder mdDec = new MarketDataTickDecoder();

                final Thread producer = new Thread(() -> {
                    final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
                    final MessageHeaderEncoder hEnc = new MessageHeaderEncoder();
                    final MarketDataTickEncoder enc = new MarketDataTickEncoder();
                    while (!done.get()) {
                        enc.wrapAndApplyHeader(buf, 0, hEnc)
                            .putSymbol(HSI_SYM, 0)
                            .exchange(Exchange.HKEX)
                            .price(190_000_000L)
                            .qty(100L)
                            .timestamp(System.nanoTime());
                        pubIn.offer(buf, 0, MD_MSG_LEN);
                    }
                }, "producer-C");
                producer.setDaemon(true);

                final Thread consumer = new Thread(() -> {
                    final UnsafeBuffer fvBuf = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
                    final MessageHeaderEncoder fvHEnc = new MessageHeaderEncoder();
                    final FvUpdateEncoder fvEnc = new FvUpdateEncoder();

                    final FragmentHandler handler =
                        (DirectBuffer buf, int off, int len, Header hdr) -> {
                            hdrDec.wrap(buf, off);
                            if (hdrDec.templateId() != MarketDataTickDecoder.TEMPLATE_ID) return;
                            mdDec.wrap(buf, off + HDR_LEN, hdrDec.blockLength(), hdrDec.version());

                            final long t0 = System.nanoTime();
                            final long spot  = mdDec.price();
                            final long pv    = divPv.get();
                            final long fv    = FuturesFvCalculator.computeFv(spot, RISK_FREE_BPS, DAYS_TO_EXP, pv);
                            final long basis = spot - fv;
                            final long bps   = FuturesFvCalculator.annualisedBasisBps(spot, fv, spot, DAYS_TO_EXP);

                            fvEnc.wrapAndApplyHeader(fvBuf, 0, fvHEnc)
                                .putSymbol(HSI_SYM, 0)
                                .exchange(mdDec.exchange())
                                .navPerUnit(0L)
                                .futuresFv(fv)
                                .basis(basis)
                                .annualisedBasisBps(bps)
                                .timestamp(mdDec.timestamp());
                            pubOut.offer(fvBuf, 0, FV_MSG_LEN);

                            histo.record(System.nanoTime() - t0);
                            recv.incrementAndGet();
                        };

                    while (!done.get()) {
                        subIn.poll(handler, FRAGMENT_LIMIT);
                    }
                    drain(subIn, handler);
                }, "consumer-C");
                consumer.setDaemon(true);

                producer.start();
                consumer.start();

                sleepSeconds(WARMUP_SECS);
                recv.set(0); histo.reset();

                sleepSeconds(MEASURE_SECS);
                done.set(true);

                producer.join(2000);
                consumer.join(2000);
            }
            return buildResult("C: FvEngine Hot Path", recv.get(), MEASURE_SECS, histo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            aeron.close();
            driver.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  D: Strategy Evaluation (in-process, no IPC)
    // ═══════════════════════════════════════════════════════════════════════════
    private static Result scenarioStrategyEval() {
        System.out.println("  [D] Strategy Evaluation (in-process) ...");

        final HkexBasisArb strategy = new HkexBasisArb(5000L, 1000L, 100L, new AtomicLong(100));
        final OrderSink noop = new OrderSink() {
            public void send(String s, Side d, long p, long q, OrderType t) {}
            public void sendLeg(long b, int i, String s, Side d, long p, long q, OrderType t) {}
        };

        final UnsafeBuffer fvBuf = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
        final MessageHeaderEncoder fvHEnc = new MessageHeaderEncoder();
        final FvUpdateEncoder fvEnc = new FvUpdateEncoder();
        fvEnc.wrapAndApplyHeader(fvBuf, 0, fvHEnc)
            .putSymbol(HSI_SYM, 0)
            .exchange(Exchange.HKEX)
            .navPerUnit(0L)
            .futuresFv(190_000_000L)
            .basis(500_000L)
            .annualisedBasisBps(6000L)
            .timestamp(System.nanoTime());

        final MessageHeaderDecoder fvHDec = new MessageHeaderDecoder();
        fvHDec.wrap(fvBuf, 0);
        final int blockLen = fvHDec.blockLength();
        final int ver      = fvHDec.version();

        final Histogram histo = new Histogram();
        long iterations = 0;

        System.setOut(NULL_STREAM);
        try {
            final long warmupEnd = System.nanoTime() + WARMUP_SECS * 1_000_000_000L;
            while (System.nanoTime() < warmupEnd) {
                final FvUpdateDecoder dec = new FvUpdateDecoder();
                dec.wrap(fvBuf, HDR_LEN, blockLen, ver);
                strategy.onFvUpdate(dec, noop);
            }

            final long measureEnd = System.nanoTime() + MEASURE_SECS * 1_000_000_000L;
            while (System.nanoTime() < measureEnd) {
                final FvUpdateDecoder dec = new FvUpdateDecoder();
                dec.wrap(fvBuf, HDR_LEN, blockLen, ver);
                final long t0 = System.nanoTime();
                strategy.onFvUpdate(dec, noop);
                histo.record(System.nanoTime() - t0);
                iterations++;
            }
        } finally {
            System.setOut(REAL_OUT);
        }

        return buildResult("D: Strategy Eval", iterations, MEASURE_SECS, histo);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  E: Full Pipeline (MD -> FV -> Strategy)
    // ═══════════════════════════════════════════════════════════════════════════
    private static Result scenarioFullPipeline() {
        System.out.println("  [E] Full Pipeline (MD -> FV -> Strategy) ...");
        final MediaDriver driver = launchDriver();
        final Aeron aeron = connectAeron(driver);
        try {
            final AtomicLong recv = new AtomicLong(0);
            final AtomicBoolean done = new AtomicBoolean(false);
            final Histogram histo = new Histogram();
            final AtomicLong divPv = new AtomicLong(0);

            final HkexBasisArb strategy = new HkexBasisArb(5000L, 1000L, 100L, new AtomicLong(100));
            final OrderSink noop = new OrderSink() {
                public void send(String s, Side d, long p, long q, OrderType t) {}
                public void sendLeg(long b, int i, String s, Side d, long p, long q, OrderType t) {}
            };

            try (Publication pub = aeron.addPublication(CHANNEL, STREAM_E);
                 Subscription sub = aeron.addSubscription(CHANNEL, STREAM_E)) {

                while (!pub.isConnected()) Thread.onSpinWait();

                final MessageHeaderDecoder hdrDec = new MessageHeaderDecoder();
                final MarketDataTickDecoder mdDec = new MarketDataTickDecoder();

                final Thread producer = new Thread(() -> {
                    final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
                    final MessageHeaderEncoder hEnc = new MessageHeaderEncoder();
                    final MarketDataTickEncoder enc = new MarketDataTickEncoder();
                    while (!done.get()) {
                        enc.wrapAndApplyHeader(buf, 0, hEnc)
                            .putSymbol(HSI_SYM, 0)
                            .exchange(Exchange.HKEX)
                            .price(190_000_000L)
                            .qty(100L)
                            .timestamp(System.nanoTime());
                        pub.offer(buf, 0, MD_MSG_LEN);
                    }
                }, "producer-E");
                producer.setDaemon(true);

                final Thread consumer = new Thread(() -> {
                    final UnsafeBuffer fvBuf = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
                    final MessageHeaderEncoder fvHEnc = new MessageHeaderEncoder();
                    final FvUpdateEncoder fvEnc = new FvUpdateEncoder();
                    final MessageHeaderDecoder fvHDec = new MessageHeaderDecoder();
                    final FvUpdateDecoder fvDec = new FvUpdateDecoder();

                    final FragmentHandler handler =
                        (DirectBuffer buf, int off, int len, Header hdr) -> {
                            hdrDec.wrap(buf, off);
                            if (hdrDec.templateId() != MarketDataTickDecoder.TEMPLATE_ID) return;
                            mdDec.wrap(buf, off + HDR_LEN, hdrDec.blockLength(), hdrDec.version());

                            final long t0 = System.nanoTime();
                            final long spot  = mdDec.price();
                            final long pv    = divPv.get();
                            final long fv    = FuturesFvCalculator.computeFv(spot, RISK_FREE_BPS, DAYS_TO_EXP, pv);
                            final long basis = spot - fv;
                            final long bps   = FuturesFvCalculator.annualisedBasisBps(spot, fv, spot, DAYS_TO_EXP);

                            fvEnc.wrapAndApplyHeader(fvBuf, 0, fvHEnc)
                                .putSymbol(HSI_SYM, 0)
                                .exchange(mdDec.exchange())
                                .navPerUnit(0L)
                                .futuresFv(fv)
                                .basis(basis)
                                .annualisedBasisBps(bps)
                                .timestamp(System.nanoTime());

                            fvHDec.wrap(fvBuf, 0);
                            fvDec.wrap(fvBuf, HDR_LEN, fvHDec.blockLength(), fvHDec.version());

                            strategy.onFvUpdate(fvDec, noop);

                            recv.incrementAndGet();
                            histo.record(System.nanoTime() - t0);
                        };

                    System.setOut(NULL_STREAM);
                    try {
                        long warmupEnd = System.nanoTime() + WARMUP_SECS * 1_000_000_000L;
                        while (System.nanoTime() < warmupEnd) {
                            sub.poll(handler, FRAGMENT_LIMIT);
                        }
                        recv.set(0); histo.reset();

                        long measureEnd = System.nanoTime() + MEASURE_SECS * 1_000_000_000L;
                        while (System.nanoTime() < measureEnd && !done.get()) {
                            sub.poll(handler, FRAGMENT_LIMIT);
                        }
                        drain(sub, handler);
                    } finally {
                        System.setOut(REAL_OUT);
                    }
                }, "consumer-E");
                consumer.setDaemon(true);

                producer.start();
                consumer.start();

                // Wait for consumer to finish its own measurement window
                consumer.join((WARMUP_SECS + MEASURE_SECS + 5) * 1000L);
                done.set(true);
                producer.join(2000);
            }
            return buildResult("E: Full Pipeline", recv.get(), MEASURE_SECS, histo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            aeron.close();
            driver.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private static void drain(final Subscription sub, final FragmentHandler handler) {
        for (int i = 0; i < 100; i++) {
            if (sub.poll(handler, FRAGMENT_LIMIT) == 0) break;
        }
    }

    private static MediaDriver launchDriver() {
        return MediaDriver.launchEmbedded(new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true));
    }

    private static Aeron connectAeron(final MediaDriver driver) {
        return Aeron.connect(new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName()));
    }

    private static Result buildResult(final String label, final long count,
                                       final int seconds, final Histogram histo) {
        return new Result(label, count / (double) seconds,
            histo.p50Us(), histo.p99Us(), histo.maxUs());
    }

    private static void sleepSeconds(final int s) {
        try { Thread.sleep(s * 1000L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Reporting
    // ═══════════════════════════════════════════════════════════════════════════

    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║    myIndexArbAlgoWorks — System Capacity Test (Phase A)     ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printSummary(final Result[] results) {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║                               CAPACITY SUMMARY                                          ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║ Scenario                                 │ msgs/sec      │ P50       │ P99      │ MAX   ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════════════════════╣");

        double minThroughput = Double.MAX_VALUE;
        String bottleneck = "";

        for (final Result r : results) {
            final String p50 = Double.isNaN(r.p50Us) ? "      —" : String.format("%6.1fµs", r.p50Us);
            final String p99 = Double.isNaN(r.p99Us) ? "      —" : String.format("%6.1fµs", r.p99Us);
            final String max = Double.isNaN(r.maxUs) ? "    —" : String.format("%5.1fµs", r.maxUs);
            System.out.printf("  ║ %-42s │ %,11.0f │ %s │ %s │ %s ║%n",
                r.label, r.msgsPerSec, p50, p99, max);
            if (!Double.isNaN(r.p99Us) && r.msgsPerSec < minThroughput) {
                minThroughput = r.msgsPerSec;
                bottleneck = r.label;
            }
        }

        if (minThroughput == Double.MAX_VALUE) {
            minThroughput = results[results.length - 1].msgsPerSec;
            bottleneck = results[results.length - 1].label;
        }

        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("  ║ BOTTLENECK: %-76s ║%n", bottleneck);
        System.out.printf("  ║ Max sustained system capacity: ~%,.0f events/sec                                   ║%n", minThroughput);
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private record Result(String label, double msgsPerSec, double p50Us, double p99Us, double maxUs) {}

    // ═══════════════════════════════════════════════════════════════════════════
    //  Histogram (microsecond buckets)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final class Histogram {
        private static final long[] BOUNDS_US = {1, 5, 10, 50, 100, 500, 1_000};
        private static final int BUCKETS = BOUNDS_US.length + 1;

        private final long[] counts = new long[BUCKETS];
        private long total   = 0;
        private long maxNs   = 0;

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

        double p50Us() { return percentileUs(0.50); }
        double p99Us() { return percentileUs(0.99); }
        double maxUs() { return maxNs / 1_000.0; }

        private double percentileUs(final double p) {
            if (total == 0) return 0;
            final long target = (long)(total * p);
            long cum = 0;
            for (int i = 0; i < BOUNDS_US.length; i++) {
                cum += counts[i];
                if (cum >= target) return BOUNDS_US[i];
            }
            return maxNs / 1_000.0;
        }
    }
}
